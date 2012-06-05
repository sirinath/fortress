(*******************************************************************************
    Copyright 2011, Oracle and/or its affiliates.
    All rights reserved.


    Use is subject to license terms.

    This distribution may include materials developed by third parties.

 ******************************************************************************)

api CompilerAlgebra

(*) trait Comparison comprises { LessThan , GreaterThan, EqualTo, Unordered } end
(*) object LessThan extends Comparison end
(*) object GreaterThan extends Comparison end
(*) object EqualTo extends Comparison end
(*) object Unordered extends Comparison end 
  
trait StandardTotalOrder[\T extends StandardTotalOrder[\T\]\] comprises T
  abstract opr <(self, other:T): Boolean
  abstract opr >(self, other:T): Boolean
  abstract opr <=(self, other:T): Boolean
  abstract opr >=(self, other:T): Boolean
  abstract opr CMP(self, other:T): TotalComparison
end  

trait Equality[\T\] comprises T
  opr =(self, other:T): Boolean
end

end
