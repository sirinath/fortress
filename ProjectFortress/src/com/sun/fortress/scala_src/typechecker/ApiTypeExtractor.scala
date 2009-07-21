/*******************************************************************************
    Copyright 2009 Sun Microsystems, Inc.,
    4150 Network Circle, Santa Clara, California 95054, U.S.A.
    All rights reserved.

    U.S. Government Rights - Commercial software.
    Government users are subject to the Sun Microsystems, Inc. standard
    license agreement and applicable provisions of the FAR and its supplements.

    Use is subject to license terms.

    This distribution may include materials developed by third parties.

    Sun, Sun Microsystems, the Sun logo and Java are trademarks or registered
    trademarks of Sun Microsystems, Inc. in the U.S. and other countries.
 ******************************************************************************/

package com.sun.fortress.scala_src.typechecker

import _root_.java.util.ArrayList
import com.sun.fortress.compiler.GlobalEnvironment
import com.sun.fortress.compiler.index.ApiIndex
import com.sun.fortress.compiler.index.ComponentIndex
import com.sun.fortress.compiler.index.ObjectTraitIndex
import com.sun.fortress.compiler.index.TraitIndex
import com.sun.fortress.exceptions.StaticError
import com.sun.fortress.exceptions.TypeError
import com.sun.fortress.nodes._
import com.sun.fortress.nodes_util.NodeUtil
import com.sun.fortress.scala_src.nodes._
import com.sun.fortress.scala_src.useful.Lists._
import com.sun.fortress.scala_src.useful.Options._
import com.sun.fortress.scala_src.useful.Sets._

/* Get types from exported APIs.
 * PropertyDecl is not yet handled.
 * Abstract function declarations coverage is not yet handled.
 * Varargs types are not yet handled.
 */
class ApiTypeExtractor(component: ComponentIndex,
                       globalEnv: GlobalEnvironment) extends Walker {

  var exports = Map[APIName, ApiIndex]()
  var inTraitOrObject: Option[IdOrOpOrAnonymousName] = None

  def check() = {
    for ( api <- toSet(component.exports) ) {
      exports += ((api, globalEnv.api(api)))
    }
    walk(component.ast)
  }

  val errors = new ArrayList[StaticError]()
  def getErrors() = errors
  private def error(s:String, n:Node) = errors.add(TypeError.make(s,n))

  private def isExported(o: ObjectDecl): Option[(APIName, Type)] = {
    val name = NodeUtil.getName(o)
    for ( api <- exports.keySet ) {
      val typeConses = exports.get(api).get.typeConses
      if ( typeConses.keySet.contains(name) &&
           NodeUtil.isObject(typeConses.get(name)) ) {
        val od = typeConses.get(name).ast.asInstanceOf[ObjectDecl]
        toOption(od.getParams) match {
          case Some(_) => return Some((api, NodeUtil.getParamType(od).unwrap))
          case _ =>
        }
      }
    }
    None
  }

  private def isExported(f: FnDecl, isMethod: Option[IdOrOpOrAnonymousName]): Option[(APIName, Type, Type)] = {
    def reportFnDecl(api: APIName, fd: FnDecl) = {
      Some((api, NodeUtil.getParamType(fd), NodeUtil.getReturnType(fd).unwrap))
    }
    def reportLValue(api: APIName, lvalue: LValue) = {
      val typeInAPI = lvalue.getIdType.unwrap.asInstanceOf[ArrowType]
      Some((api, typeInAPI.getDomain, typeInAPI.getRange))
    }
    val name = NodeUtil.getName(f)
    if ( isMethod.isDefined ) {
      val owner = isMethod.get
      for ( api <- exports.keySet ) {
        val typeConses = exports.get(api).get.typeConses
        if ( typeConses.keySet.contains(owner) &&
             NodeUtil.isTraitOrObject(typeConses.get(owner)) ) {
          val tindex = typeConses.get(owner).asInstanceOf[TraitIndex]
          if ( tindex.getters.keySet.contains(name) )
            return reportFnDecl(api, tindex.getters.get(name).ast.asInstanceOf[FnDecl])
          if ( tindex.setters.keySet.contains(name) )
            return reportFnDecl(api, tindex.setters.get(name).ast.asInstanceOf[FnDecl])
          if ( tindex.dottedMethods.firstSet.contains(name) )
            for ( g <- toSet(tindex.dottedMethods.matchFirst(name)) ) {
              g match {
                case DeclaredMethod(fd,_) => return reportFnDecl(api, fd)
                case _ =>
              }
            }
          if ( tindex.functionalMethods.firstSet.contains(name) )
            for ( g <- toSet(tindex.functionalMethods.matchFirst(name)) ) {
              g match {
                case FunctionalMethod(fd,_) => return reportFnDecl(api, fd)
                case _ =>
              }
            }
          if ( NodeUtil.isObject(typeConses.get(owner)) &&
               typeConses.get(owner).asInstanceOf[ObjectTraitIndex].fields.keySet.contains(name) )
            typeConses.get(owner).asInstanceOf[ObjectTraitIndex].fields.get(name) match {
              case DeclaredVariable(lvalue) => return reportLValue(api, lvalue)
              case _ =>
            }
        }
      }
    } else {
      for ( api <- exports.keySet ) {
        val functions = exports.get(api).get.functions
        val variables = exports.get(api).get.variables
        if ( functions.firstSet.contains(name) )
          for ( g <- toSet(functions.matchFirst(name)) ) {
            g match {
              case DeclaredFunction(fd) => return reportFnDecl(api, fd)
              case _ =>
            }
          }
        // Check abstract function declarations of the form of variable declarations
        if ( variables.keySet.contains(name) )
          variables.get(name) match {
            case DeclaredVariable(lvalue) => return reportLValue(api, lvalue)
            case _ =>
          }
      }
    }
    None
  }

  private def forParams(params: List[Param], paramTypeInAPI: Type) =
    params.length match {
      case 0 => params
      case 1 => List(forParam((params.head, paramTypeInAPI)))
      case _ =>
        params.zip(toList(paramTypeInAPI.asInstanceOf[TupleType].getElements)).map(forParam)
    }

  private def forParam(pair:(Param, Type)) = pair._1 match {
    case SParam(info, name, mods, ty, exp, varargsTy) =>
      val newTy = ty match {
        case Some(_) => ty
        case None => Some(pair._2)
      }
      // Varargs type is not yet handled...
      SParam(info, name, mods, newTy, exp, varargsTy)
    case p => p
  }

  private def forLValue(v: LValue, isField: Option[IdOrOpOrAnonymousName]): LValue = v match {
    case SLValue(info, name, mods, ty, mutable) =>
      if ( ty.isNone ) {
        if ( isField.isDefined ) {
          val owner = isField.get
          for ( api <- exports.keySet ) {
            val typeConses = exports.get(api).get.typeConses
            if ( typeConses.keySet.contains(owner) &&
                 NodeUtil.isObject(typeConses.get(owner)) ) {
              val tindex = typeConses.get(owner).asInstanceOf[ObjectTraitIndex]
              if ( tindex.fields.keySet.contains(name) )
                tindex.fields.get(name) match {
                  case DeclaredVariable(lvalue) =>
                    return SLValue(info, name, mods, lvalue.getIdType, mutable)
                }
            }
          }
          v
        } else {
          for ( api <- exports.keySet ) {
            val variables = exports.get(api).get.variables
            if ( variables.keySet.contains(name) )
              variables.get(name) match {
                case DeclaredVariable(vInAPI) =>
                  return SLValue(info, name, mods, vInAPI.getIdType, mutable)
              }
          }
          v
        }
      } else v
    case _ => v
  }

  private def paramWithoutType(p: Param) =
    p.getIdType.isNone && p.getVarargsType.isNone

  override def walk(node:Any):Any = {
    node match {
      case t@STraitDecl(info,
                        STraitTypeHeader(sparams, mods, name, where,
                                         throwsC, contract, extendsC, decls),
                        excludes, comprises, ellipses, self) =>
        inTraitOrObject = Some(name)
        val newHeader = STraitTypeHeader(sparams, mods, name, where,
                                         throwsC, contract, extendsC,
                                         walk(decls).asInstanceOf[List[Decl]])
        inTraitOrObject = None
        STraitDecl(info, newHeader, excludes, comprises, ellipses, self)

      case o@SObjectDecl(info,
                         STraitTypeHeader(sparams, mods, name, where,
                                          throwsC, contract, extendsC, decls),
                         params, self) =>
        inTraitOrObject = Some(name)
        val newHeader = STraitTypeHeader(sparams, mods, name, where,
                                         throwsC, contract, extendsC,
                                         walk(decls).asInstanceOf[List[Decl]])
        inTraitOrObject = None
        if ( params.isSome && params.unwrap.exists(paramWithoutType) )
          isExported(o) match {
            // If this object o is exported by an API api
            case Some((api, paramTypeInAPI)) =>
              SObjectDecl(info, newHeader,
                          Some(forParams(params.unwrap, paramTypeInAPI)), self)
            case _ => SObjectDecl(info, newHeader, params, self)
          }
        else SObjectDecl(info, newHeader, params, self)

      case f@SFnDecl(info,
                     SFnHeader(sparams, mods, name, where,
                               throwsC, contract, params, returnType),
                     unambiguousName, body, impName) =>
        if ( NodeUtil.getReturnType(f).isNone ||
             toList(NodeUtil.getParams(f)).exists(paramWithoutType) ) {
          isExported(f, inTraitOrObject) match {
            // If this function f is exported by an API api
            case Some((api, paramTypeInAPI, returnTypeInAPI)) =>
              val newReturnType = returnType match {
                case Some(_) => returnType
                // and f does not have a return type annotation,
                // get the type from the API api
                case _ => Some(returnTypeInAPI)
              }
              SFnDecl(info,
                      SFnHeader(sparams, mods, name, where, throwsC, contract,
                                forParams(params, paramTypeInAPI), newReturnType),
                      unambiguousName, body, impName)
            case _ => f
          }
        } else f

      case v@SVarDecl(info, lhs, body) =>
        SVarDecl(info, lhs.map(forLValue(_, inTraitOrObject)), body)

      case _ => super.walk(node)
    }
  }
}