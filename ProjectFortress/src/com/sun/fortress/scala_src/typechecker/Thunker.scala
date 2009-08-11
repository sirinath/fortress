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

import _root_.java.util.{Set => JavaSet}
import com.sun.fortress.compiler.index.{Unit=>JUnit,_}
import com.sun.fortress.exceptions.InterpreterBug.bug
import com.sun.fortress.nodes._
import com.sun.fortress.scala_src.nodes._
import com.sun.fortress.scala_src.useful.Options._
import com.sun.fortress.scala_src.useful.ErrorLog
import com.sun.fortress.scala_src.useful.Sets._
import edu.rice.cs.plt.collect.Relation
import edu.rice.cs.plt.collect.UnionRelation
import edu.rice.cs.plt.lambda.{LazyThunk, Thunk}
import edu.rice.cs.plt.tuple.{Option => JOption}
import scala.collection.mutable.Stack


class Thunker(var typeChecker: STypeChecker)
             (implicit val cycleChecker: CyclicReferenceChecker) {
  
  type TypeThunk = Thunk[JOption[Type]]
  
  def walk(node: Node):Unit = node match {
    
    case SComponent(info, name, imports, decls, comprises, isNative, exports) =>
      decls.map(walk(_))

    case v@SVarDecl(info, lhs, rhs) => {
      // Create a TryChecker and prime the indices for this decl.
      val tryChecker = STypeCheckerFactory.makeTryChecker(typeChecker)
      val ids = lhs.map(lv => lv.getName)
      val variables = ids.map(id =>
        typeChecker.current.variables.get(id).asInstanceOf[DeclaredVariable])
      Thunker.primeVariables(variables, v, tryChecker)
    }

    case t@STraitDecl(info,
                      STraitTypeHeader(sparams, mods, name, where, throwsC, contract, extendsC, decls),
                      excludes, comprises, hasEllipses, selfType) => {
      typeChecker = typeChecker.extend(sparams, where)
      // Add field declarations (getters/setters?) to method_checker
      typeChecker = decls.foldRight(typeChecker)
                                      {(d:Decl, c:STypeChecker) => d match {
                                        case SVarDecl(_,lhs,_) => c.extend(lhs)
                                        case _ => c }}
      toOption(typeChecker.traits.typeCons(name.asInstanceOf[Id])) match {
        case None => ()
        case Some(ti) =>
          // Extend method checker with methods and functions
          // that will now be in scope
          val inheritedMethods = typeChecker.inheritedMethods(extendsC)
          val functionalMethods = ti.asInstanceOf[TraitIndex].functionalMethods
          val dottedMethods = ti.asInstanceOf[TraitIndex]
                                .dottedMethods.asInstanceOf[Relation[IdOrOpOrAnonymousName, Method]]
          val methods = new UnionRelation(inheritedMethods,
                                          dottedMethods)
          typeChecker = typeChecker.extendWithFunctions(methods)
          // Extend method checker with self
          selfType match {
            case Some(ty) =>
              typeChecker = typeChecker.addSelf(ty)
            case _ =>
          }
          //Create a tryChecker
          val tryChecker = STypeCheckerFactory.makeTryChecker(typeChecker)
          //Prime all the dotted and functional method indices)
          Thunker.primeFunctionals(dottedMethods.secondSet,tryChecker)
          Thunker.primeFunctionals(functionalMethods.secondSet,tryChecker)
      }
    }
    
    case o@SObjectDecl(info,
                       STraitTypeHeader(sparams, mods, name, where, throwsC, contract, extendsC, decls),
                       params, selfType) => {
      typeChecker = typeChecker.extend(sparams, where)
      // Add field declarations (getters/setters?) to method_checker
      typeChecker = decls.foldRight(typeChecker)
                                      {(d:Decl, c:STypeChecker) => d match {
                                        case SVarDecl(_,lhs,_) => c.extend(lhs)
                                        case _ => c }}
      toOption(typeChecker.traits.typeCons(name.asInstanceOf[Id])) match {
        case None => ()
        case Some(to) =>
          // Extend method checker with methods and functions
          // that will now be in scope
          val inheritedMethods = typeChecker.inheritedMethods(extendsC)
          val functionalMethods = to.asInstanceOf[TraitIndex].functionalMethods
          val dottedMethods = to.asInstanceOf[TraitIndex]
                                .dottedMethods.asInstanceOf[Relation[IdOrOpOrAnonymousName, Method]]
          val methods = new UnionRelation(inheritedMethods,
                                          dottedMethods)
          typeChecker = typeChecker.extendWithFunctions(methods)
          // Extend method checker with self
          selfType match {
            case Some(ty) =>
              typeChecker = typeChecker.addSelf(ty)
            case _ =>
          }
          //Create a TryChecker
          val tryChecker = STypeCheckerFactory.makeTryChecker(typeChecker)
          //Prime all the dotted and functional method indices
          Thunker.primeFunctionals(dottedMethods.secondSet,tryChecker)
          Thunker.primeFunctionals(functionalMethods.secondSet,tryChecker)
    }
  }
    
    case _ =>  
  }
  
}

object Thunker {

  type TypeThunk = Thunk[JOption[Type]]

  /** For each index in the set, construct and insert its thunk. */
  def primeFunctionals[T<:Functional]
      (fns: JavaSet[T], tryChecker: TryChecker)
      (implicit cycleChecker: CyclicReferenceChecker) =

    toSet(fns).foreach(fn => {
      if (!fn.hasThunk)
      fn match {
        case m:Method =>
          m.putThunk(makeThunk(m.ast.asInstanceOf[FnDecl], m, tryChecker))
        case d:DeclaredFunction =>
          d.putThunk(makeThunk(d.ast.asInstanceOf[FnDecl], d, tryChecker))
        case f:FunctionalMethod =>
          f.putThunk(makeThunk(f.ast.asInstanceOf[FnDecl], f, tryChecker))
        case _ =>
      }
    })

  /** Make the thunk used for the given functional that checks its body. */
  def makeThunk(decl: FnDecl,
                index: Functional,
                tryChecker: TryChecker)
               (implicit cycleChecker: CyclicReferenceChecker): TypeThunk =

    new TypeThunk() {
      def value() = {
        if (cycleChecker.push(index)) {
          tryChecker.tryCheck(decl) match {
            case Some(node) =>
              cycleChecker.pop
              // Any function without a body must have a return type
              node.asInstanceOf[FnDecl].getBody.unwrap.getInfo.getExprType

            case None =>
              cycleChecker.pop
              none[Type]
          }
        }
        else
          none[Type]
      }
    }

  /** Put the thunk in all the given variables for this decl. */
  def primeVariables(vars: List[DeclaredVariable],
                     decl: VarDecl,
                     tryChecker: TryChecker)
                    (implicit cycleChecker: CyclicReferenceChecker): Unit = {

    // Create a shared, caching thunk that checks the decl.
    val sharedThunk = makeSharedThunk(decl, tryChecker)
    for (index <- vars) {

      // Create the thunk for this particular index.
      val thunk = new TypeThunk() {
        def value() = {
          if (cycleChecker.push(index)) {

            // Evaluate shared thunk to get type of RHS and pop this index.
            val rhsType = toOption(sharedThunk.value)
            cycleChecker.pop

            // Get this variable's type out of the RHS type.
            rhsType match {
              case Some(t:TupleType) => some(t.getElements.get(index.position))
              case Some(t) => some(t)
              case None => none[Type]
            }
          }
          else
            none[Type]
        }
      }
      
      index.putThunk(thunk)
    }
  }


  /** This thunk actually checks the decl and is shared by all vars. */
  def makeSharedThunk(decl: VarDecl,
                      tryChecker: TryChecker): TypeThunk =

    // Make a single caching thunk for all vars in the decl to share.
    LazyThunk.make[JOption[Type]](new TypeThunk() {
      def value() = {
        tryChecker.tryCheck(decl) match {
          case Some(node) =>
            // Any variable without a RHS must have a declared type
            node.asInstanceOf[VarDecl].getInit.unwrap.getInfo.getExprType

          case None => none[Type]
        }
    }})


}

/**
 * Checks for cyclic references to names whose types must be inferred. When
 * inferring types of declarations, we need to determine if there is a
 * cyclic dependency like the following:
 *
 *   x = f()
 *   f() = ... x ...
 */
class CyclicReferenceChecker(val errors: ErrorLog) {
  
  protected val stack = new Stack[InferredTypeIndex]
  
  def push(index: InferredTypeIndex): Boolean = {

    // Check if this index is in the stack; if so, error. else push
    if (stack.contains(index)) {
      val cycle = stack.dropWhile(_ != index) ++ List(index)
      val cycleStr = cycle.mkString(", ")
      val kind = index match {
        case _:Variable => "variable"
        case _:ParametricOperator => "parametric operator"
        case _:FunctionalMethod => "functional method"
        case _:Function => "function"
        case _:Method => "method"
        case _ => "index"
      }
      errors.signal("Cannot infer type for %s %s because it has reference cycle: %s".format(kind, index, cycleStr), index.getSpan)
      false
    } else {
      stack.push(index)
      true
    }
  }
  
  def pop(): InferredTypeIndex = stack.pop
  
}