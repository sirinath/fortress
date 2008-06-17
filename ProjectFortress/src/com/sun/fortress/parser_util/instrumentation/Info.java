/*******************************************************************************
    Copyright 2008 Sun Microsystems, Inc.,
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

package com.sun.fortress.parser_util.instrumentation;

import java.util.Collection;
import java.util.LinkedList;
import java.util.List;

/*
 * Class given a ??? Rats module generates instrumented versions.
 * Intended as almost a drop-in replacement for xtc.parser.Rats.
 */

public class Info {

    public static class ModuleInfo {
        public final String module;
        public final List<ProductionInfo> productions = new LinkedList<ProductionInfo>();
        public ModuleInfo(List<ModuleInfo> allModules, String module) {
            this.module = module;
            allModules.add(this);
        }
    }

    public static class ProductionInfo {
        public final ModuleInfo module;
        public final String production;
        public final List<SequenceInfo> sequences = new LinkedList<SequenceInfo>();
        public ProductionInfo(ModuleInfo module, String production) {
            this.module = module;
            this.production = production;
            module.productions.add(this);
        }
    }

    public static class SequenceInfo {
        public final ProductionInfo production;
        public final String sequence;
        public final int sequenceIndex;
        public int startedCount = 0;
        public int endedCount = 0;
        public int committedCount = 0;
        public SequenceInfo(ProductionInfo production, String sequence, int sequenceIndex) {
            this.production = production;
            this.sequence = sequence;
            this.sequenceIndex = sequenceIndex;
            production.sequences.add(this);
        }

        public boolean registerOccurrence(xtc.util.State s) {
            ((State)s).add(this);
            return true;
        }
    }
}
