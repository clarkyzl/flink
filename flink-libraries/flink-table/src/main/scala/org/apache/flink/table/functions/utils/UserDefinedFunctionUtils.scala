/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */


package org.apache.flink.table.functions.utils

import java.lang.{Integer => JInt, Long => JLong}
import java.lang.reflect.{Method, Modifier}
import java.sql.{Date, Time, Timestamp}

import org.apache.commons.codec.binary.Base64
import com.google.common.primitives.Primitives
import org.apache.calcite.rel.`type`.{RelDataType, RelDataTypeFactory}
import org.apache.calcite.rex.{RexLiteral, RexNode}
import org.apache.calcite.sql.SqlFunction
import org.apache.calcite.sql.`type`.SqlTypeName
import org.apache.flink.api.common.functions.InvalidTypesException
import org.apache.flink.api.common.typeinfo.{AtomicType, TypeInformation}
import org.apache.flink.api.common.typeutils.CompositeType
import org.apache.flink.api.java.typeutils.TypeExtractor
import org.apache.flink.table.calcite.FlinkTypeFactory
import org.apache.flink.table.api.{TableEnvironment, TableException, ValidationException}
import org.apache.flink.table.expressions.{Expression, Literal, TableFunctionCall}
import org.apache.flink.table.functions.{ScalarFunction, TableFunction, UserDefinedFunction}
import org.apache.flink.table.plan.schema.{DeferredTypeFlinkTableFunction, FlinkTableFunction}
import org.apache.flink.util.InstantiationUtil

import scala.collection.JavaConversions._

object UserDefinedFunctionUtils {

  /**
    * Checks if a user-defined function can be easily instantiated.
    */
  def checkForInstantiation(clazz: Class[_]): Unit = {
    if (!InstantiationUtil.isPublic(clazz)) {
      throw ValidationException("Function class is not public.")
    }
    else if (!InstantiationUtil.isProperClass(clazz)) {
      throw ValidationException("Function class is no proper class, it is either abstract," +
        " an interface, or a primitive type.")
    }
    else if (InstantiationUtil.isNonStaticInnerClass(clazz)) {
      throw ValidationException("The class is an inner class, but not statically accessible.")
    }
  }

  /**
    * Check whether this is a Scala object. It is forbidden to use [[TableFunction]] implemented
    * by a Scala object, since concurrent risks.
    */
  def checkNotSingleton(clazz: Class[_]): Unit = {
    // TODO it is not a good way to check singleton. Maybe improve it further.
    if (clazz.getFields.map(_.getName) contains "MODULE$") {
      throw new ValidationException(
        s"TableFunction implemented by class ${clazz.getCanonicalName} " +
          s"is a Scala object, it is forbidden since concurrent risks.")
    }
  }

  // ----------------------------------------------------------------------------------------------
  // Utilities for eval methods
  // ----------------------------------------------------------------------------------------------

  /**
    * Returns signatures matching the given signature of [[TypeInformation]].
    * Elements of the signature can be null (act as a wildcard).
    */
  def getSignature(
      function: UserDefinedFunction,
      signature: Seq[TypeInformation[_]])
    : Option[Array[Class[_]]] = {
    getEvalMethod(function, signature).map(_.getParameterTypes)
  }

  /**
    * Returns eval method matching the given signature of [[TypeInformation]].
    */
  def getEvalMethod(
      function: UserDefinedFunction,
      signature: Seq[TypeInformation[_]])
    : Option[Method] = {
    // We compare the raw Java classes not the TypeInformation.
    // TypeInformation does not matter during runtime (e.g. within a MapFunction).
    val actualSignature = typeInfoToClass(signature)
    val evalMethods = checkAndExtractEvalMethods(function)

    val filtered = evalMethods
      // go over all eval methods and filter out matching methods
      .filter {
        case cur if !cur.isVarArgs =>
          val signatures = cur.getParameterTypes
          // match parameters of signature to actual parameters
          actualSignature.length == signatures.length &&
            signatures.zipWithIndex.forall { case (clazz, i) =>
              parameterTypeEquals(actualSignature(i), clazz)
          }
        case cur if cur.isVarArgs =>
          val signatures = cur.getParameterTypes
          actualSignature.zipWithIndex.forall {
            // non-varargs
            case (clazz, i) if i < signatures.length - 1  =>
              parameterTypeEquals(clazz, signatures(i))
            // varargs
            case (clazz, i) if i >= signatures.length - 1 =>
              parameterTypeEquals(clazz, signatures.last.getComponentType)
          } || (actualSignature.isEmpty && signatures.length == 1) // empty varargs
    }

    // if there is a fixed method, compiler will call this method preferentially
    val fixedMethodsCount = filtered.count(!_.isVarArgs)
    val found = filtered.filter { cur =>
      fixedMethodsCount > 0 && !cur.isVarArgs ||
      fixedMethodsCount == 0 && cur.isVarArgs
    }

    // check if there is a Scala varargs annotation
    if (found.isEmpty &&
      evalMethods.exists { evalMethod =>
        val signatures = evalMethod.getParameterTypes
        signatures.zipWithIndex.forall {
          case (clazz, i) if i < signatures.length - 1 =>
            parameterTypeEquals(actualSignature(i), clazz)
          case (clazz, i) if i == signatures.length - 1 =>
            clazz.getName.equals("scala.collection.Seq")
        }
      }) {
      throw new ValidationException("Scala-style variable arguments in 'eval' methods are not " +
        "supported. Please add a @scala.annotation.varargs annotation.")
    } else if (found.length > 1) {
      throw new ValidationException("Found multiple 'eval' methods which match the signature.")
    }
    found.headOption
  }

  /**
    * Check if a given method exists in the given function
    */
  def ifMethodExistInFunction(method: String, function: UserDefinedFunction): Boolean = {
    val methods = function
      .getClass
      .getMethods
      .filter {
        m => m.getName == method
      }
    !methods.isEmpty
  }

  /**
    * Extracts "eval" methods and throws a [[ValidationException]] if no implementation
    * can be found, or implementation does not match the requirements.
    */
  def checkAndExtractEvalMethods(function: UserDefinedFunction): Array[Method] = {
    val methods = function
      .getClass
      .getDeclaredMethods
      .filter { m =>
        val modifiers = m.getModifiers
        m.getName == "eval" &&
          Modifier.isPublic(modifiers) &&
          !Modifier.isAbstract(modifiers) &&
          !(function.isInstanceOf[TableFunction[_]] && Modifier.isStatic(modifiers))
      }

    if (methods.isEmpty) {
      throw new ValidationException(
        s"Function class '${function.getClass.getCanonicalName}' does not implement at least " +
          s"one method named 'eval' which is public, not abstract and " +
          s"(in case of table functions) not static.")
    }

    methods
  }

  def getSignatures(function: UserDefinedFunction): Array[Array[Class[_]]] = {
    checkAndExtractEvalMethods(function).map(_.getParameterTypes)
  }

  // ----------------------------------------------------------------------------------------------
  // Utilities for SQL functions
  // ----------------------------------------------------------------------------------------------

  /**
    * Create [[SqlFunction]] for a [[ScalarFunction]]
    *
    * @param name function name
    * @param function scalar function
    * @param typeFactory type factory
    * @return the ScalarSqlFunction
    */
  def createScalarSqlFunction(
      name: String,
      function: ScalarFunction,
      typeFactory: FlinkTypeFactory)
    : SqlFunction = {
    new ScalarSqlFunction(name, function, typeFactory)
  }

  /**
    * Create [[SqlFunction]]s for a [[TableFunction]]'s every eval method
    *
    * @param name function name
    * @param tableFunction table function
    * @param implicitResultType the implicit type information of returned table
    * @param typeFactory type factory
    * @return the TableSqlFunction
    */
  def createTableSqlFunctions(
      name: String,
      tableFunction: TableFunction[_],
      implicitResultType: TypeInformation[_],
      typeFactory: FlinkTypeFactory)
    : Seq[SqlFunction] = {
    val evalMethods = checkAndExtractEvalMethods(tableFunction)

    evalMethods.map { method =>
      // we don't know the exact result type yet.
      val function = new DeferredTypeFlinkTableFunction(
        tableFunction, method, implicitResultType)
      TableSqlFunction(name, tableFunction, implicitResultType, typeFactory, function)
    }
  }

  // ----------------------------------------------------------------------------------------------
  // Utilities for scalar functions
  // ----------------------------------------------------------------------------------------------

  /**
    * Internal method of [[ScalarFunction#getResultType()]] that does some pre-checking and uses
    * [[TypeExtractor]] as default return type inference.
    */
  def getResultType(
      function: ScalarFunction,
      signature: Array[Class[_]])
    : TypeInformation[_] = {
    // find method for signature
    val evalMethod = checkAndExtractEvalMethods(function)
      .find(m => signature.sameElements(m.getParameterTypes))
      .getOrElse(throw new ValidationException("Given signature is invalid."))

    val userDefinedTypeInfo = function.getResultType(signature)
    if (userDefinedTypeInfo != null) {
      userDefinedTypeInfo
    } else {
      try {
        TypeExtractor.getForClass(evalMethod.getReturnType)
      } catch {
        case ite: InvalidTypesException =>
          throw new ValidationException(
            s"Return type of scalar function '${function.getClass.getCanonicalName}' cannot be " +
              s"automatically determined. Please provide type information manually.")
      }
    }
  }

  /**
    * Returns the return type of the evaluation method matching the given signature.
    */
  def getResultTypeClass(
      function: ScalarFunction,
      signature: Array[Class[_]])
    : Class[_] = {
    // find method for signature
    val evalMethod = checkAndExtractEvalMethods(function)
      .find(m => signature.sameElements(m.getParameterTypes))
      .getOrElse(throw new IllegalArgumentException("Given signature is invalid."))
    evalMethod.getReturnType
  }

  // ----------------------------------------------------------------------------------------------
  // Miscellaneous
  // ----------------------------------------------------------------------------------------------

  /**
    * Returns field names and field positions for a given [[TypeInformation]].
    *
    * Field names are automatically extracted for
    * [[org.apache.flink.api.common.typeutils.CompositeType]].
    *
    * @param inputType The TypeInformation to extract the field names and positions from.
    * @return A tuple of two arrays holding the field names and corresponding field positions.
    */
  def getFieldInfo(inputType: TypeInformation[_])
    : (Array[String], Array[Int], Array[TypeInformation[_]]) = {

    (TableEnvironment.getFieldNames(inputType),
    TableEnvironment.getFieldIndices(inputType),
    TableEnvironment.getFieldTypes(inputType))
  }

  /**
    * Prints one signature consisting of classes.
    */
  def signatureToString(signature: Array[Class[_]]): String =
  signature.map { clazz =>
    if (clazz == null) {
      "null"
    } else {
      clazz.getCanonicalName
    }
  }.mkString("(", ", ", ")")

  /**
    * Prints one signature consisting of TypeInformation.
    */
  def signatureToString(signature: Seq[TypeInformation[_]]): String = {
    signatureToString(typeInfoToClass(signature))
  }

  /**
    * Prints all eval methods signatures of a class.
    */
  def signaturesToString(function: UserDefinedFunction): String = {
    getSignatures(function).map(signatureToString).mkString(", ")
  }

  /**
    * Extracts type classes of [[TypeInformation]] in a null-aware way.
    */
  private def typeInfoToClass(typeInfos: Seq[TypeInformation[_]]): Array[Class[_]] =
  typeInfos.map { typeInfo =>
    if (typeInfo == null) {
      null
    } else {
      typeInfo.getTypeClass
    }
  }.toArray

  /**
    * Compares parameter candidate classes with expected classes. If true, the parameters match.
    * Candidate can be null (acts as a wildcard).
    */
  private def parameterTypeEquals(candidate: Class[_], expected: Class[_]): Boolean =
  candidate == null ||
    candidate == expected ||
    expected == classOf[Object] ||
    expected.isPrimitive && Primitives.wrap(expected) == candidate ||
    candidate == classOf[Date] && (expected == classOf[Int] || expected == classOf[JInt])  ||
    candidate == classOf[Time] && (expected == classOf[Int] || expected == classOf[JInt]) ||
    candidate == classOf[Timestamp] && (expected == classOf[Long] || expected == classOf[JLong])

  @throws[Exception]
  def serialize(function: UserDefinedFunction): String = {
    val byteArray = InstantiationUtil.serializeObject(function)
    Base64.encodeBase64URLSafeString(byteArray)
  }

  @throws[Exception]
  def deserialize(data: String): UserDefinedFunction = {
    val byteData = Base64.decodeBase64(data)
    InstantiationUtil
      .deserializeObject[UserDefinedFunction](byteData, Thread.currentThread.getContextClassLoader)
  }

  /**
    * Build a TableFunctionCall, a name and a sequence of params will determine a unique
    * [[TableFunctionCall]]
    *
    * @param name function name
    * @param implicitResultType If no result type returned, it will use this type.
    * @param params The input expressions
    * @return A unique [[TableFunctionCall]]
    */
  private[table] def buildTableFunctionCall(
      name: String,
      tableFunction: TableFunction[_],
      implicitResultType: TypeInformation[_],
      params: Expression*): TableFunctionCall = {
    val arguments = transformLiteralExpressions(params: _*)
    val userDefinedResultType = tableFunction.getResultType(arguments)
    val resultType = {
      if (userDefinedResultType != null) userDefinedResultType
      else implicitResultType
    }
    TableFunctionCall(name, tableFunction, params, resultType)
  }

  /**
    * Transform the expressions to Objects
    * Only literal expressions will be transformed, non-literal expressions will be
    * translated to nulls.
    *
    * @param params actual parameters of the function
    * @return A java List of the Objects
    */
  private[table] def transformLiteralExpressions(params: Expression*): java.util.List[AnyRef] = {
    params.map {
      case exp: Literal =>
        exp.value.asInstanceOf[AnyRef]
      case _ =>
        null
    }
  }

  /**
    * Transform the rex nodes to Objects
    * Only literal rex nodes will be transformed, non-literal rex nodes will be
    * translated to nulls.
    *
    * @param rexNodes actual parameters of the function
    * @return A java List of the Objects
    */
  private[table] def transformRexNodes(
      rexNodes: java.util.List[RexNode]): java.util.List[AnyRef] = {
    rexNodes.map {
      case rexNode: RexLiteral =>
        val value = rexNode.getValue2
        rexNode.getType.getSqlTypeName match {
          case SqlTypeName.INTEGER =>
            value.asInstanceOf[Long].toInt.asInstanceOf[AnyRef]
          case SqlTypeName.SMALLINT =>
            value.asInstanceOf[Long].toShort.asInstanceOf[AnyRef]
          case SqlTypeName.TINYINT =>
            value.asInstanceOf[Long].toByte.asInstanceOf[AnyRef]
          case SqlTypeName.FLOAT =>
            value.asInstanceOf[Double].toFloat.asInstanceOf[AnyRef]
          case SqlTypeName.REAL =>
            value.asInstanceOf[Double].toFloat.asInstanceOf[AnyRef]
          case _ =>
            value.asInstanceOf[AnyRef]
        }
      case _ =>
        null
    }
  }

  private [table] def buildRelDataType(
      typeFactory: RelDataTypeFactory,
      resultType: TypeInformation[_],
      fieldNames: Array[String],
      fieldIndexes: Array[Int]): RelDataType = {

    if (fieldIndexes.length != fieldNames.length) {
      throw new TableException(
        "Number of field indexes and field names must be equal.")
    }

    // check uniqueness of field names
    if (fieldNames.length != fieldNames.toSet.size) {
      throw new TableException(
        "Table field names must be unique.")
    }

    val fieldTypes: Array[TypeInformation[_]] =
      resultType match {
        case cType: CompositeType[_] =>
          if (fieldNames.length != cType.getArity) {
            throw new TableException(
              s"Arity of type (" + cType.getFieldNames.deep + ") " +
                  "not equal to number of field names " + fieldNames.deep + ".")
          }
          fieldIndexes.map(cType.getTypeAt(_).asInstanceOf[TypeInformation[_]])
        case aType: AtomicType[_] =>
          if (fieldIndexes.length != 1 || fieldIndexes(0) != 0) {
            throw new TableException(
              "Non-composite input type may have only a single field and its index must be 0.")
          }
          Array(aType)
      }

    val flinkTypeFactory = typeFactory.asInstanceOf[FlinkTypeFactory]
    val builder = flinkTypeFactory.builder
    fieldNames
        .zip(fieldTypes)
        .foreach { f =>
          builder.add(f._1, flinkTypeFactory.createTypeFromTypeInfo(f._2)).nullable(true)
        }
    builder.build
  }
}
