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

package com.sun.fortress.scala_src.useful
import com.sun.fortress.compiler.GlobalEnvironment
import com.sun.fortress.compiler.WellKnownNames._
import com.sun.fortress.compiler.index._
import com.sun.fortress.compiler.typechecker.TypeAnalyzer
import com.sun.fortress.compiler.Types.ANY
import com.sun.fortress.compiler.Types.BOTTOM
import com.sun.fortress.compiler.Types.OBJECT
import com.sun.fortress.nodes._
import com.sun.fortress.nodes_util.NodeFactory._
import com.sun.fortress.nodes_util.NodeUtil._
import com.sun.fortress.scala_src.nodes._
import com.sun.fortress.scala_src.typechecker.TraitTable
import com.sun.fortress.scala_src.useful.Lists._
import com.sun.fortress.scala_src.useful.Maps._
import com.sun.fortress.scala_src.useful.Options._
import com.sun.fortress.scala_src.useful.Sets._
import com.sun.fortress.scala_src.useful.STypesUtil._

import edu.rice.cs.plt.collect.CollectUtil;

import scala.util.parsing.combinator._

object TypeParser extends RegexParsers {
  val ID = """[A-Z]([a-zA-Z0-9]|_[a-zA-Z0-9])+"""r
  val VAR = """[A-Z]"""r
  
  def typeSchema: Parser[Type] = opt(staticParams) ~ typ ^^
    {case ps~t => insertStaticParams(t, ps.getOrElse(Nil))}

  def arrowTypeSchema: Parser[ArrowType] = opt(staticParams) ~ arrowType ^^
    {case ps~t => insertStaticParams(t, ps.getOrElse(Nil)).asInstanceOf[ArrowType]}

  def traitSchema: Parser[TraitType] = opt(staticParams) ~ traitType ^^
    {case ps~t => insertStaticParams(t, ps.getOrElse(Nil)).asInstanceOf[TraitType]}

  def staticParams: Parser[List[StaticParam]] = "[" ~> repsep(staticParam, ",") <~ "]"
  def staticParam: Parser[StaticParam] = regex(VAR) ~ opt("<:" ~> baseType) ^^
    {case id~bounds => makeTypeParam(typeSpan, makeId(typeSpan, id), toJavaList(List(bounds.getOrElse(OBJECT))), none[Type], false)}
  
  def typ: Parser[Type] = arrowType | nonArrowType
  
  def arrowType: Parser[ArrowType] = nonArrowType ~ "->" ~ typ ^^
    {case dom~_~ran => makeArrowType(typeSpan,dom, ran)}
    
  def nonArrowType: Parser[Type] = baseType | parenthesizedType | tupleType
  
  def parenthesizedType: Parser[Type] = "(" ~> typ <~ ")"
  
  def tupleType: Parser[TupleType] = "(" ~> repsep(typ, ",") <~ ")" ^^
    {typs => makeTupleType(toJavaList(typs))}
  
  def baseType: Parser[BaseType] = traitType | varType
  
  def varType: Parser[VarType] = regex(VAR) ^^ 
    {id => makeVarType(typeSpan, id)}
  
  def traitType: Parser[TraitType] = regex(ID) ~ opt(staticArgs) ^^ 
    {case id~args => makeTraitType(typeSpan, id, toJavaList(args.getOrElse(Nil)))}
  def staticArgs: Parser[List[StaticArg]] = "[" ~> repsep(staticArg, ",") <~ "]"
  def staticArg: Parser[StaticArg] = typ ^^ 
    {t => makeTypeArg(typeSpan, t)}
  
  def traitIndex: Parser[TraitIndex] = traitSchema ~ 
    opt("extends {" ~> repsep(baseType, ",") <~ "}") ~
    opt("excludes {" ~> repsep(baseType, ",") <~ "}") ~
    opt("comprises {" ~> repsep(baseType, ",") <~ "}") ^^
    {case tType~mSupers~mExcludes~mComprises => 
      val supers = mSupers.getOrElse(Nil)
      val excludes = mExcludes.getOrElse(Nil)
      val superWheres = supers.map(makeTraitTypeWhere(_, none[WhereClause]))
      val ast = makeTraitDecl(tType, toJavaList(superWheres), toJavaList(excludes),
                              toJavaOption(mComprises.map(toJavaList(_))))
      new ProperTraitIndex(ast,
                           toJavaMap(Map()),
                           toJavaMap(Map()),
                           toJavaSet(Set()),
                           CollectUtil.emptyRelation[IdOrOpOrAnonymousName,DeclaredMethod],
                           CollectUtil.emptyRelation[IdOrOpOrAnonymousName,FunctionalMethod])}
  
  def typeAnalyzer: Parser[TypeAnalyzer] = "{" ~> repsep(traitIndex, ",") <~ "}" ^^
    {traits => 
      val component = makeComponentIndex("OverloadingTest", traits)
      new TypeAnalyzer(new TraitTable(component, GLOBAL_ENV))
    }
  
  def overloadingSet: Parser[List[ArrowType]] = "{" ~> repsep(arrowTypeSchema, ",") <~ "}"
  
  def makeComponentIndex(name: String, traits: List[TraitIndex]): ComponentIndex = {
    val traitDecls = traits.map(_.ast.asInstanceOf[Decl])
    val traitMap = Map(traits.map(t=> (getName(t.ast),t)):_*)
    val ast = makeComponent(typeSpan, makeAPIName(typeSpan, name),
                              toJavaList(Nil), toJavaList(traitDecls), toJavaList(Nil))
    new ComponentIndex(ast,
                       toJavaMap(Map()),
                       toJavaSet(Set()),
                       CollectUtil.emptyRelation[IdOrOpOrAnonymousName, Function],
                       toJavaSet(Set()),
                       toJavaMap(traitMap),
                       toJavaMap(Map()),
                       toJavaMap(Map()),
                       0)
  }
  
  def makeApiIndex(name: String, traits: List[TraitIndex]): ApiIndex = {
    val traitDecls = traits.map(_.ast.asInstanceOf[Decl])
    val traitMap = Map(traits.map(t=> (getName(t.ast),t)):_*)
    val ast = makeApi(typeSpan, makeAPIName(typeSpan, name),
                      toJavaList(Nil), toJavaList(traitDecls));
    return new ApiIndex(ast,
                        toJavaMap(Map()),
                        CollectUtil.emptyRelation[IdOrOpOrAnonymousName, Function],
                        toJavaSet(Set()),
                        toJavaMap(traitMap),
                        toJavaMap(Map()),
                        toJavaMap(Map()),
                        toJavaMap(Map()),
                        0);
  }
  
  val GLOBAL_ENV = {
    val any = makeApiIndex(anyTypeLibrary, List(parse(traitIndex, "Any").get))
    val obj = makeApiIndex(fortressBuiltin, List(parse(traitIndex, "Object").get))
    new GlobalEnvironment.FromMap(toJavaMap(Map((any.ast.getName, any), (obj.ast.getName, obj))))
  }
  
}