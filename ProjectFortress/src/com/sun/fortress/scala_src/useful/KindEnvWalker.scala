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

import com.sun.fortress.nodes._
import com.sun.fortress.nodes_util.{NodeUtil => NU}
import com.sun.fortress.scala_src.nodes._
import com.sun.fortress.scala_src.typechecker.staticenv.KindEnv
import com.sun.fortress.scala_src.useful.Lists._

/**
 * Walk an AST, maintaining the correct kind environment. Any node that
 * introduces static parameters will change the kind environment, so client
 * code (subclasses of this class) can inject code to be executed with that env.
 */
class KindEnvWalker extends Walker {

  /** The kind environment at the current node in the tree. */
  protected var env: KindEnv = KindEnv.makeFresh

  /**
   * Walk the tree, extending the kind environment with static parameters where
   * they are introduced.
   */
  override def walk(node: Any) = node match {
    case d:FnDecl =>
      withStaticParams(toListFromImmutable(NU.getStaticParams(d)), super.walk(d))
    case d:TraitObjectDecl =>
      withStaticParams(toListFromImmutable(NU.getStaticParams(d)), super.walk(d))
    case _ => super.walk(node)
  }

  /**
   * Evaluate the body code in the environment extended with the given static
   * params. Capture the current environment, extend it, evaluate the body with
   * the extended environment, restore the original environment, and return the
   * evaluated result.
   */
  private def withStaticParams(sparams: List[StaticParam], body: => Any): Any = {
    val currentEnv = env
    env = env.extend(sparams)
    val result = body
    env = currentEnv
    result
  }
}