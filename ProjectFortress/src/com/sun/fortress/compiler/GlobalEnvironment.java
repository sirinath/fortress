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

package com.sun.fortress.compiler;

import com.sun.fortress.repository.FortressRepository;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.Map;
import com.sun.fortress.compiler.index.ApiIndex;
import com.sun.fortress.exceptions.FortressException;
import com.sun.fortress.exceptions.WrappedException;
import com.sun.fortress.nodes.APIName;
import com.sun.fortress.nodes_util.NodeUtil;
import com.sun.fortress.useful.NI;

/**
 * Environment for mapping APINames to ApiIndices.
 * Before looking up an APIName, the client is required to first ensure that
 * the APIName is in the environment. This can be done by calling the
 * definesApi method.
 */
abstract public class GlobalEnvironment {
    abstract public Map<APIName, ApiIndex> apis();

    abstract public boolean definesApi(APIName name);

    abstract public ApiIndex api(APIName name);

    abstract public void print();

    public String toString() {
        return this.getClass().getSimpleName() + " " + apis();
    }

    public static class FromMap extends GlobalEnvironment {
        private Map<APIName, ApiIndex> _apis;

        public FromMap(Map<APIName, ApiIndex> apis) { _apis = apis; }

        public Map<APIName, ApiIndex> apis() { return _apis; }

        public boolean definesApi(APIName name) { return _apis.containsKey(name); }

        public ApiIndex api(APIName name) {
            ApiIndex result = _apis.get(name);
            if (result == null) {
                throw new IllegalArgumentException("Undefined API: " +
                                                   NodeUtil.nameString(name));
            }
            else { return result; }
        }

        public void print() {
            for (APIName name : apis().keySet()) {
                System.out.println(name);
            }
        }
    }
    public static class FromRepository extends GlobalEnvironment {

        final private FortressRepository repository;

        public FromRepository(FortressRepository fr) {
            repository = fr;
        }

        @Override
        public ApiIndex api(APIName name) {

            try {
                return repository.getApi(name);
            } catch (FileNotFoundException e) {
                throw new WrappedException(e);
            } catch (IOException e) {
                throw new WrappedException(e);
            }
        }

        @Override
        public Map<APIName, ApiIndex> apis() {
            // TODO Auto-generated method stub
            return repository.apis();
        }

        @Override
        public boolean definesApi(APIName name) {
            try {
                return null != repository.getApi(name);
            } catch (FileNotFoundException e) {
                return false;
            } catch (IOException e) {
                return false;
            }
        }

        @Override
        public void print() {
            System.out.println("GlobalEnvironmentFromRepository " + repository);
        }

    }

    public static class FromRepositoryPair extends GlobalEnvironment {

        final private FortressRepository r1, r2;

        /**
         * Tries to use the result form the first repository, but does not try hard.
         * If missing, tries (harder) to use the second repository.
         * @param fr1
         * @param fr2
         */
        public FromRepositoryPair(FortressRepository fr1, FortressRepository fr2) {
            r1 = fr1;
            r2 = fr2;
        }

        @Override
        public ApiIndex api(APIName name) {

            try {
                ApiIndex ai = r1.apis().get(name);
                if (ai != null)
                    return ai;
                return r2.getApi(name);
            } catch (FileNotFoundException e) {
                throw new WrappedException(e);
            } catch (IOException e) {
                throw new WrappedException(e);
            }
        }

        @Override
        public Map<APIName, ApiIndex> apis() {
            // TODO Auto-generated method stub
            return NI.nyi("Thought this was not called.");
        }

        @Override
        public boolean definesApi(APIName name) {
            try {
                ApiIndex ai = r1.apis().get(name);
            if (ai != null)
                 return true;
              return null != r2.getApi(name);
            } catch (FileNotFoundException e) {
                return false;
            } catch (IOException e) {
                return false;
            }
        }

        @Override
        public void print() {
            System.out.println("GlobalEnvironmentFromRepositoryPair " + r1 + ", " + r2);
        }

    }

}
