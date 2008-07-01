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

package com.sun.fortress;

import com.sun.fortress.repository.CacheBasedRepository;
import com.sun.fortress.repository.FortressRepository;
import com.sun.fortress.repository.ProjectProperties;
import java.io.*;
import java.util.*;
import edu.rice.cs.plt.tuple.Option;
import edu.rice.cs.plt.iter.IterUtil;
import edu.rice.cs.plt.collect.CollectUtil;

import com.sun.fortress.repository.GraphRepository;
import com.sun.fortress.compiler.*;
import com.sun.fortress.exceptions.shell.UserError;
import com.sun.fortress.exceptions.StaticError;
import com.sun.fortress.exceptions.WrappedException;
import com.sun.fortress.exceptions.ProgramError;
import com.sun.fortress.exceptions.FortressException;
import com.sun.fortress.exceptions.shell.RepositoryError;
import com.sun.fortress.compiler.Parser;
import com.sun.fortress.compiler.index.ApiIndex;
import com.sun.fortress.compiler.index.ComponentIndex;
import com.sun.fortress.compiler.environments.TopLevelEnvGen;
import com.sun.fortress.nodes.Api;
import com.sun.fortress.nodes.APIName;
import com.sun.fortress.nodes.CompilationUnit;
import com.sun.fortress.nodes.Component;
import com.sun.fortress.nodes_util.NodeFactory;
import com.sun.fortress.nodes_util.ASTIO;
import com.sun.fortress.interpreter.Driver;
import com.sun.fortress.syntax_abstractions.phases.GrammarRewriter;
import com.sun.fortress.useful.Path;
import com.sun.fortress.useful.Debug;

import static com.sun.fortress.useful.ConvenientStrings.*;

public final class Shell {
    static boolean test;

    private final FortressRepository _repository;
    public static FortressRepository CURRENT_INTERPRETER_REPOSITORY = null;

    public FortressRepository getRepository() {
        return _repository;
    }

    /**
     * This is used to communicate, clumsily, with parsers generated by syntax expansion.
     * The interface should be improved.
     */
    public static void setCurrentInterpreterRepository( FortressRepository g ){
        CURRENT_INTERPRETER_REPOSITORY = g;
    }

    public static FortressRepository specificRepository(Path p, FortressRepository cache ){
        FortressRepository fr = new GraphRepository( p, cache );
        CURRENT_INTERPRETER_REPOSITORY = fr;
        return fr;
    }

    public static FortressRepository specificRepository(Path p) {
        return specificRepository( p, new CacheBasedRepository(ProjectProperties.ANALYZED_CACHE_DIR) );
    }

    public Shell() {
        _repository = new CacheBasedRepository(ProjectProperties.ANALYZED_CACHE_DIR);
    }

    public Shell(FortressRepository repository) { _repository = repository; }

    /* Helper method to print usage message.*/
    private static void printUsageMessage() {
        System.err.println("Usage:");
        System.err.println(" compile [-out file] [-debug [#]] somefile.fs{s,i}");
        System.err.println(" [run] [-test] [-debug [#]] somefile.fss arg...");
        System.err.println(" parse [-out file] [-debug [#]] somefile.fs{s,i}...");
        System.err.println(" typecheck [-out file] [-debug [#]] somefile.fs{s,i}...");
        System.err.println(" help");
    }

    private static void printHelpMessage() {
        System.err.println
        ("Invoked as script: fortress args\n"+
         "Invoked by java: java ... com.sun.fortress.Shell args\n"+
         "fortress compile [-out file] [-debug [#]] somefile.fs{s,i}\n"+
         "  Compile somefile. If compilation succeeds no message will be printed.\n"+
         "   -out file : dumps the processed abstract syntax tree to a file.\n" +
         "   -debug : enables debugging to the maximum level and prints java stack traces.\n"+
         "   -debug # : sets debugging to the specified level, where # is a number.\n"+
         "\n"+
         "fortress [run] [-test] [-debug [#]] somefile.fss arg ...\n"+
         "  Runs somefile.fss through the Fortress interpreter, passing arg ... to the\n"+
         "  run method of somefile.fss.\n"+
         "   -test : first runs test functions associated with the program.\n"+
         "   -debug : enables debugging to the maximum level and prints java stack traces.\n"+
         "   -debug # : sets debugging to the specified level, where # is a number.\n"+
         "\n"+
         "fortress parse [-out file] [-debug [#]] somefile.fs{i,s}\n"+
         "  Parses a file. If parsing succeeds the message \"Ok\" will be printed.\n"+
         "  If -out file is given, a message about the file being written to will be printed.\n"+
         "   -out file : dumps the abstract syntax tree to a file.\n"+
         "   -debug : enables debugging to the maximum level and prints java stack traces.\n"+
         "   -debug # : sets debugging to the specified level, where # is a number.\n"+
         "\n"+
         "fortress typecheck [-out file] [-debug [#]] somefile.fs{i,s}\n"+
         "  Typechecks a file. If type checking succeeds no message will be printed.\n"+
         "   -out file : dumps the processed abstract syntax tree to a file.\n"+
         "   -debug : enables debugging to the maximum level and prints java stack traces.\n"+
         "   -debug # : sets debugging to the specified level, where # is a number.\n"
        );
    }

    private static void turnOnTypeChecking(){
        com.sun.fortress.compiler.StaticChecker.typecheck = true;
    }

    /* Main entry point for the fortress shell.*/
    public static void main(String[] tokens) throws InterruptedException, Throwable {
        if (tokens.length == 0) {
            printUsageMessage();
            System.exit(-1);
        }

        // Now match the assembled string.
        try {
            String what = tokens[0];
            List<String> args = Arrays.asList(tokens).subList(1, tokens.length);
            if (what.equals("compile")) {
                compile(args, Option.<String>none());
            } else if (what.equals("run")) {
                run(args);
            } else if ( what.equals("parse" ) ){
                parse(args, Option.<String>none());
            } else if (what.equals("typecheck")) {
                turnOnTypeChecking();
                compile(args, Option.<String>none());
            } else if (what.contains(ProjectProperties.COMP_SOURCE_SUFFIX)
                       || (what.startsWith("-") && tokens.length > 1)) {
                // no "run" command.
                run(Arrays.asList(tokens));
            } else if (what.equals("help")) {
                printHelpMessage();

            } else { printUsageMessage(); }
        }
        catch (UserError error) {
            System.err.println(error.getMessage());
        }
        catch (IOException error) {
            System.err.println(error.getMessage());
        }
    }

    /**
     * Parse a file. If the file parses ok it will say "Ok".
     * If you want a dump then give -out somefile.
     */
    private static void parse(List<String> args, Option<String> out)
        throws UserError, InterruptedException, IOException {
        if (args.size() == 0) {
            throw new UserError("Need a file to compile");
        }
        String s = args.get(0);
        List<String> rest = args.subList(1, args.size());

        if (s.startsWith("-")) {
            if (s.equals("-debug")){
                Debug.setDebug( 99 );
                if ( ! rest.isEmpty() && isInteger( rest.get( 0 ) ) ){
                    Debug.setDebug( Integer.valueOf( rest.get( 0 ) ) );
                    rest = rest.subList( 1, rest.size() );
                } else {
                    ProjectProperties.debug = true;
                }
            }
            if (s.equals("-out") && ! rest.isEmpty() ){
                out = Option.<String>some(rest.get(0));
                rest = rest.subList( 1, rest.size() );
            }
            if (s.equals("-noPreparse")) ProjectProperties.noPreparse = true;

            parse( rest, out );
        } else {
            parse( s, out );
        }
    }

    private static void parse( String file, Option<String> out){
        try{
            CompilationUnit unit = Parser.parseFile(cuName(file), new File(file));
            System.out.println( "Ok" );
            if ( out.isSome() ){
                try{
                    ASTIO.writeJavaAst(unit, out.unwrap());
                    System.out.println( "Dumped parse tree to " + out.unwrap() );
                } catch ( IOException e ){
                    System.err.println( "Error while writing " + out.unwrap() );
                }
            }
        } catch ( FileNotFoundException f ){
            System.err.println( file + " not found" );
        } catch ( IOException ie ){
            System.err.println( "Error while reading " + file );
        } catch ( StaticError s ){
            System.err.println(s);
        }
    }

    private static boolean isInteger( String s ){
        try{
            int i = Integer.valueOf(s);
            return i == i;
        } catch ( NumberFormatException n ){
            return false;
        }
    }

    private static boolean isApi(String file){
        return file.endsWith(ProjectProperties.API_SOURCE_SUFFIX);
    }

    private static boolean isComponent(String file){
        return file.endsWith(ProjectProperties.COMP_SOURCE_SUFFIX);
    }

    private static APIName cuName( String file ){
        if ( file.endsWith( ProjectProperties.COMP_SOURCE_SUFFIX ) ||
             file.endsWith( ProjectProperties.API_SOURCE_SUFFIX ) ){
            return NodeFactory.makeAPIName(file.substring( 0, file.lastIndexOf(".") ));
        }
        return NodeFactory.makeAPIName(file);
    }

    /**
     * Compile a file.
     * If you want a dump then give -out somefile.
     */
    private static void compile(List<String> args, Option<String> out)
        throws UserError, InterruptedException, IOException {
        if (args.size() == 0) {
            throw new UserError("Need a file to compile");
        }
        String s = args.get(0);
        List<String> rest = args.subList(1, args.size());

        if (s.startsWith("-")) {
            if (s.equals("-debug")){
                Debug.setDebug( 99 );
                if ( ! rest.isEmpty() && isInteger( rest.get( 0 ) ) ){
                    Debug.setDebug( Integer.valueOf( rest.get( 0 ) ) );
                    rest = rest.subList( 1, rest.size() );
                } else {
                    ProjectProperties.debug = true;
                }
            }
            if (s.equals("-out") && ! rest.isEmpty() ){
                out = Option.<String>some(rest.get(0));
                rest = rest.subList(1, rest.size());
            }
            if (s.equals("-noPreparse")) ProjectProperties.noPreparse = true;
            compile(rest, out);
        } else {
            try {
                Path path = ProjectProperties.SOURCE_PATH;

                /* Questions 1)
                   1) Not for parse
                   2) What if there are multiple "/"s
                */
                if (s.contains("/")) {
                    String head = s.substring(0, s.lastIndexOf("/"));
                    s = s.substring(s.lastIndexOf("/")+1, s.length());
                    path = path.prepend(head);
                }
                Iterable<? extends StaticError> errors = compile(path, s, out );
                if ( errors.iterator().hasNext() ){
                    for (StaticError error: errors) {
                        System.err.println(error);
                    }
                }
            } catch (RepositoryError error) {
                System.err.println(error);
            }
        }
    }

    /**
     * Compile a file.
     */
    public static Iterable<? extends StaticError> compile(Path path, String file) {
        return compile(path, file, Option.<String>none());
    }

    private static Iterable<? extends StaticError> compile(Path path, String file, Option<String> out) {
        Shell shell = new Shell();
        FortressRepository bcr = specificRepository( path, shell.getRepository() );

        Debug.debug( 2, "Compiling file " + file );
        APIName name = cuName(file);
        try {
            if ( isApi(file) ) {
                Api a = (Api) bcr.getApi(name).ast();
                if ( out.isSome() )
                    ASTIO.writeJavaAst(shell.getRepository().getApi(name).ast(), out.unwrap());
            } else if (isComponent(file)) {
                Component c = (Component) bcr.getComponent(name).ast();
                if ( out.isSome() )
                    ASTIO.writeJavaAst(shell.getRepository().getComponent(name).ast(), out.unwrap());
            } else {
                System.out.println( "Don't know what kind of file " + file +
                                    " is. Append .fsi or .fss." );
            }
        } catch (ProgramError pe) {
            Iterable<? extends StaticError> se = pe.getStaticErrors();
            if (se == null) {
                return IterUtil.singleton(new WrappedException(pe, ProjectProperties.debug));
            }
            else {
                return se;
            }
        } catch (RepositoryError ex) {
            throw ex;
        } catch ( FileNotFoundException ex ){
            throw new WrappedException(ex);
        } catch ( IOException e ){
            throw new WrappedException(e);
        } catch (StaticError ex) {
             return IterUtil.singleton(new WrappedException(ex, ProjectProperties.debug));
        }

        if (bcr.verbose())
            System.err.println("Compiling done.");

        return IterUtil.empty();
    }

    /**
     * Run a file.
     */
    private static void run(List<String> args)
        throws UserError, IOException, Throwable {
        if (args.size() == 0) {
            throw new UserError("Need a file to run");
        }
        String s = args.get(0);
        List<String> rest = args.subList(1, args.size());

        if (s.startsWith("-")) {
            if (s.equals("-debug")){
                Debug.setDebug( 99 );
                if ( ! rest.isEmpty() && isInteger( rest.get( 0 ) ) ){
                    Debug.setDebug( Integer.valueOf( rest.get( 0 ) ) );
                    rest = rest.subList( 1, rest.size() );
                } else {
                    ProjectProperties.debug = true;
                }
            }
            if (s.equals("-test")) test = true;
            if (s.equals("-noPreparse")) ProjectProperties.noPreparse = true;
            run(rest);
        } else {
            run(s, rest);
        }
    }

    private static void run(String fileName, List<String> args)
        throws UserError, Throwable {
        try {
            Shell shell = new Shell();
            Path path = ProjectProperties.SOURCE_PATH;

            if (fileName.contains("/")) {
                String head = fileName.substring(0, fileName.lastIndexOf("/"));
                fileName = fileName.substring(fileName.lastIndexOf("/")+1, fileName.length());
                path = path.prepend(head);
            }

            APIName componentName = cuName(fileName);
            FortressRepository bcr = specificRepository( path, shell.getRepository() );
            Iterable<? extends StaticError> errors = IterUtil.empty();

            try {
                CompilationUnit cu = bcr.getLinkedComponent(componentName).ast();
                Driver.runProgram(bcr, cu, test, args);
            } catch (Throwable th) {
                // TODO FIXME what is the proper treatment of errors/exceptions etc.?
                if (th instanceof FortressException) {
                    FortressException pe = (FortressException) th;
                    if (pe.getStaticErrors() != null)
                        errors = pe.getStaticErrors();
                }
                if (th instanceof RuntimeException)
                    throw (RuntimeException) th;
                if (th instanceof Error)
                    throw (Error) th;
                throw new WrappedException(th, ProjectProperties.debug);
            }

            for (StaticError error: errors) {
                System.err.println(error);
            }
            // If there are no errors, all components will have been written to disk by the CacheBasedRepository.
        } catch ( StaticError e ){
            System.err.println(e);
            if ( ProjectProperties.debug ){
                e.printStackTrace();
            }
        } catch (RepositoryError e) {
            System.err.println(e.getMessage());
        } catch (FortressException e) {
            System.err.println(e.getMessage());
            e.printInterpreterStackTrace(System.err);
            if (ProjectProperties.debug) {
                e.printStackTrace();
            } else {
                System.err.println("Turn on -debug for Java-level error dump.");
            }
            System.exit(1);
        }
    }

    public static Iterable<? extends StaticError> analyze(FortressRepository _repository,
                                                          GlobalEnvironment env,
                                                          Iterable<Api> apis,
                                                          Iterable<Component> components,
                                                          long lastModified) {
        String phase = "";

        // Build ApiIndices before disambiguating to allow circular references.
        // An IndexBuilder.ApiResult contains a map of strings (names) to
        // ApiIndices.
        IndexBuilder.ApiResult rawApiIR = IndexBuilder.buildApis(apis, lastModified);
        if (!rawApiIR.isSuccessful()) { return rawApiIR.errors(); }

        // Build ComponentIndices before disambiguating to allow circular references.
        // An IndexBuilder.ComponentResult contains a map of strings (names) to
        // ComponentIndices.
        IndexBuilder.ComponentResult rawComponentIR =
            IndexBuilder.buildComponents(components, lastModified);
        if (!rawComponentIR.isSuccessful()) { return rawComponentIR.errors(); }

        // Build a new GlobalEnvironment consisting of all APIs in a global
        // repository combined with all APIs that have been processed in the previous
        // step.  For now, we are implementing pure static linking, so there is
        // no global repository.
        GlobalEnvironment rawApiEnv =
            new GlobalEnvironment.FromMap(CollectUtil.union(_repository.apis(),
                                                            rawApiIR.apis()));

        // Rewrite all API ASTs so they include only fully qualified names, relying
        // on the rawApiEnv constructed in the previous step. Note that, after this
        // step, the rawApiEnv is stale and needs to be rebuilt with the new API ASTs.
        Disambiguator.ApiResult apiDR =
            Disambiguator.disambiguateApis(apis, rawApiEnv);
        if (!apiDR.isSuccessful()) { return apiDR.errors(); }

        Disambiguator.ComponentResult componentDR =
            Disambiguator.disambiguateComponents(components, env,
                                                 rawComponentIR.components());
        if (!componentDR.isSuccessful()) { return componentDR.errors(); }

        if (phase.equals("disambiguate"))
            return IterUtil.empty();

        // Rebuild ApiIndices.
        IndexBuilder.ApiResult apiIR =
            IndexBuilder.buildApis(apiDR.apis(), System.currentTimeMillis());
        if (!apiIR.isSuccessful()) { return apiIR.errors(); }

        // Rebuild ComponentIndices.
        IndexBuilder.ComponentResult componentIR =
            IndexBuilder.buildComponents(componentDR.components(),
                                         System.currentTimeMillis());
        if (!componentIR.isSuccessful()) { return componentIR.errors(); }

        // Rebuild GlobalEnvironment.
        GlobalEnvironment apiEnv =
            new GlobalEnvironment.FromMap(CollectUtil.union(_repository.apis(),
                                                            apiIR.apis()));

        // Rewrite grammars, see GrammarRewriter for more details.
        GrammarRewriter.ApiResult apiID = GrammarRewriter.rewriteApis(apiIR.apis(), apiEnv);
        if (!apiID.isSuccessful()) { return apiID.errors(); }

        // Rebuild ApiIndices.
        apiIR = IndexBuilder.buildApis(apiID.apis(), System.currentTimeMillis());
        if (!apiIR.isSuccessful()) { return apiIR.errors(); }

        // Rebuild GlobalEnvironment.
        apiEnv =
            new GlobalEnvironment.FromMap(CollectUtil.union(_repository.apis(),
                                                            apiIR.apis()));

        // Do all type checking and other static checks on APIs.
        StaticChecker.ApiResult apiSR =
            StaticChecker.checkApis(apiIR.apis(), apiEnv);
        if (!apiSR.isSuccessful()) { return apiSR.errors(); }

        StaticChecker.ComponentResult componentSR =
            StaticChecker.checkComponents(componentIR.components(), env);
        if (!componentSR.isSuccessful()) { return componentSR.errors(); }

        if (phase.equals("compile"))
            return IterUtil.empty();

        Desugarer.ApiResult apiDSR =
            Desugarer.desugarApis(apiSR.apis(), apiEnv);

        // Generate top-level byte code environments
        TopLevelEnvGen.ComponentResult componentGR =
            TopLevelEnvGen.generate(componentSR.components(), env);
        if(!componentGR.isSuccessful()) { return componentGR.errors(); }

        // Generate code.  Code is stored in the _repository object.
        // In an implementation with pure static linking, we would have to write
        // this code back out to a file.
        // In an implementation with fortresses,
        // we would write this code into the resident fortress.
        for (Map.Entry<APIName, ApiIndex> newApi : apiDSR.apis().entrySet()) {
            if ( compiledApi( newApi.getKey(), apis ) ){
                Debug.debug( 2, "Analyzed api " + newApi.getKey() );
                _repository.addApi(newApi.getKey(), newApi.getValue());
            }
        }

        // Additional optimization phases can be inserted here

        for (Map.Entry<APIName, ComponentIndex> newComponent :componentSR.components().entrySet()) {
            _repository.addComponent(newComponent.getKey(), newComponent.getValue());
        }

        Debug.debug( 2, "Done with analyzing apis " + apis + ", components " + components );

        return IterUtil.empty();
    }

    private static boolean compiledApi( APIName name, Iterable<Api> apis ){
        for ( Api api : apis ){
            if ( api.getName().equals(name) ){
                return true;
            }
        }
        return false;
    }
}
