/*******************************************************************************
    Copyright 2007 Sun Microsystems, Inc.,
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

package com.sun.fortress.syntaxabstractions.rats;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.util.Date;
import java.util.LinkedList;
import java.util.List;

import xtc.parser.Module;
import xtc.parser.ModuleDependency;
import xtc.parser.ModuleImport;
import xtc.parser.ModuleList;
import xtc.parser.ModuleModification;
import xtc.parser.ModuleName;
import xtc.parser.PParser;
import xtc.parser.ParseError;
import xtc.parser.PrettyPrinter;
import xtc.parser.Production;
import xtc.parser.Result;
import xtc.parser.SemanticValue;
import xtc.tree.Comment;
import xtc.tree.Printer;
import xtc.type.JavaAST;

import com.sun.fortress.syntaxabstractions.GrammarIndex;
import com.sun.fortress.syntaxabstractions.old.RatsMacroDecl;
import com.sun.fortress.syntaxabstractions.rats.util.ModuleEnum;
import com.sun.fortress.syntaxabstractions.rats.util.ModuleInfo;
import com.sun.fortress.syntaxabstractions.rats.util.ProductionEnum;
import com.sun.fortress.useful.Useful;

import edu.rice.cs.plt.tuple.Option;

public abstract class RatsUtil {

	public static Module getRatsModule(ModuleEnum module) {
		return RatsUtil.getRatsModule(RatsUtil.getFortressSrcDir()+RatsUtil.getModulePath(ModuleInfo.getModuleName(module).name)+".rats");
	}
	
	public static Module getRatsModule(String filename) {
		Option<Module> result = parseRatsModule(filename);
		if (result.isNone()) {
			System.err.println("FAIL: Syntax error(s).");
			System.exit(1);
			return null;
		}
		else {
			System.out.println("No Syntax errors");
			return Option.unwrap(result);
		}
	}

	public static Option<Module> parseRatsModule(String filename) {
		BufferedReader in;
		try {
			in = Useful.utf8BufferedFileReader(filename);
			xtc.parser.PParser parser = new PParser(in,
					filename,
					(int) new File(filename).length());
			Result r = parser.pModule(0);
			if (r.hasValue()) {
				SemanticValue v = (SemanticValue) r;
				Module n = (Module) v.value;
				return Option.some(n);
			}
			else {
				ParseError err = (ParseError) r;
				if (-1 == err.index) {
					System.err.println("  Parse error");
				}
				else {
					System.err.println("  " + parser.location(err.index) + ": "
							+ err.msg);
				}
				return Option.none();
			}
		} catch (FileNotFoundException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		} catch (IOException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
		return Option.none();
	}

	public static void writeRatsModule(Module module, String tempDir) {
		FileOutputStream fo;
		try {
			String name = RatsUtil.getModulePath(module.name.name);
			fo = new FileOutputStream(tempDir+name+".rats");		
			PrettyPrinter pp = new PrettyPrinter(new Printer(fo), new JavaAST(), false);
			pp.visit(module);
			pp.flush();
			fo.flush();
			fo.close();
		} catch (FileNotFoundException e1) {
			// TODO Auto-generated catch block
			e1.printStackTrace();
		} catch (IOException ee) {
			// TODO Auto-generated catch block
			ee.printStackTrace();
		}
	}
	
	public static void copyFortressGrammar() {
		for (ModuleEnum moduleEnum: ModuleEnum.values()) {
			copyFortressGrammarFile(moduleEnum);
		}
	}
	
	public static void copyFortressGrammarFile(ModuleEnum moduleEnum) {
		try {
			String modulePathName = RatsUtil.getModulePath(ModuleInfo.getModuleName(moduleEnum).name)+".rats";
			copy(RatsUtil.getFortressSrcDir()+modulePathName , RatsUtil.getTempDir()+modulePathName);
		} catch (IOException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
	}
	
	private static void copy(String fromFileName, String toFileName) throws IOException {
		File fromFile = new File(fromFileName);
		File toFile = new File(toFileName);

		if (!fromFile.exists())
			throw new IOException("FileCopy: " + "no such source file: "
					+ fromFileName);
		if (!fromFile.isFile())
			throw new IOException("FileCopy: " + "can't copy directory: "
					+ fromFileName);
		if (!fromFile.canRead())
			throw new IOException("FileCopy: " + "source file is unreadable: "
					+ fromFileName);

		if (toFile.isDirectory())
			toFile = new File(toFile, fromFile.getName());

//		if (toFile.exists()) {
			if (!toFile.canWrite()) {
				throw new IOException("FileCopy: "
						+ "destination file is unwriteable: " + toFileName);
		} else {
			String parent = toFile.getParent();
			if (parent == null)
				parent = System.getProperty("user.dir");
			File dir = new File(parent);
			if (!dir.exists()) {
				boolean success = (dir).mkdirs();
		    	if (!success) {
		    		throw new IOException("FileCopy: "
						+ "destination directory doesn't exist: " + parent);
		    	}
			}
			if (dir.isFile())
				throw new IOException("FileCopy: "
						+ "destination is not a directory: " + parent);
			if (!dir.canWrite())
				throw new IOException("FileCopy: "
						+ "destination directory is unwriteable: " + parent);
		}

		FileInputStream from = null;
		FileOutputStream to = null;
		try {
			from = new FileInputStream(fromFile);
			to = new FileOutputStream(toFile);
			byte[] buffer = new byte[4096];
			int bytesRead;

			while ((bytesRead = from.read(buffer)) != -1)
				to.write(buffer, 0, bytesRead); // write
		} finally {
			if (from != null)
				try {
					from.close();
				} catch (IOException e) {
					;
				}
				if (to != null)
					try {
						to.close();
					} catch (IOException e) {
						;
					}
		}
	}

	public static Module makeExtendingRatsModule(com.sun.fortress.syntaxabstractions.intermediate.Module module) {
		Module m = new Module();
		m.name = new ModuleName(RatsUtil.getModuleNamePrefix()+module.getName());
		m.documentation = getComment();
		m.productions = new LinkedList<Production>();
		
		List<ModuleName> parameters = new LinkedList<ModuleName>();
		List<ModuleDependency> dependencies = new LinkedList<ModuleDependency>();
		// If we modify a core Fortress module then we need a lot of boiler plate 
		if (module.getModify() != null) {
			String modifyName = module.getModify().getName();
			ModuleEnum moduleEnum = ModuleInfo.getModuleFromProduction(ModuleInfo.getProductionEnum(modifyName));
		
			// Get the parameter which the Fortress grammar Rats! files use
			if (ModuleInfo.isFortressModule(modifyName)) {
				parameters = ModuleInfo.getParameters(moduleEnum);
				dependencies = ModuleInfo.getModuleModification(moduleEnum);
			}
			ModuleList args = new ModuleList(ModuleInfo.getParameters(moduleEnum));
			ModuleName moduleName = ModuleInfo.getExtendedModuleName(moduleEnum );
			m.modification = new ModuleModification(moduleName, args, null);
		}
		
		for (com.sun.fortress.syntaxabstractions.intermediate.Module imported: module.getImports()) {
			parameters.add(new ModuleName(imported.getName()));
			dependencies.add(new ModuleImport(new ModuleName(imported.getName())));
		}
		m.parameters = new ModuleList(parameters);
		m.dependencies = dependencies;
		
		
		return m;
	}

	/**
	 * @param m
	 */
	public static Comment getComment() {
		List<String> lines = new LinkedList<String>();
		lines.add("Module generated by the Fortress compiler on "+new Date());
		return new Comment(Comment.Kind.SINGLE_LINE, lines);
	}
	
	public static String getModulePath() {
		return ModuleInfo.MODULE_NAME_PREFIX.replaceAll("\\.", ""+File.separatorChar);
	}
	
	public static String getModuleNamePrefix() {
		return ModuleInfo.MODULE_NAME_PREFIX;
	}
	
	public static String getModulePath(String dottedName) {
		return dottedName.replaceAll("\\.", ""+File.separatorChar);
	}

	public static String getParserPath() {
		return RatsUtil.getFortressSrcDir()+"com"+File.separatorChar+"sun"+File.separatorChar+"fortress"+File.separatorChar+"parser"+File.separatorChar;
	}
	
	public static String getTempDir() {
		return System.getProperty("java.io.tmpdir")+File.separatorChar;
	}

	public static String getFortressSrcDir() {
		return System.getenv("FORTRESS_HOME")+File.separatorChar+"ProjectFortress"+File.separatorChar+"src"+File.separatorChar;
	}
}
