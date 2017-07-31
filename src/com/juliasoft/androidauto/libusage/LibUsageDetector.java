package com.juliasoft.androidauto.libusage;


import java.io.BufferedWriter;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarInputStream;
import java.util.stream.Stream;

import org.apache.bcel.classfile.*;
import org.apache.bcel.generic.*;
import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.CommandLineParser;
import org.apache.commons.cli.DefaultParser;
import org.apache.commons.cli.HelpFormatter;
import org.apache.commons.cli.Option;
import org.apache.commons.cli.OptionGroup;
import org.apache.commons.cli.Options;
import org.apache.commons.cli.ParseException;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.google.common.io.Files;

public class LibUsageDetector {

	final static Logger logger = LogManager.getLogger(LibUsageDetector.class.getName());
	
	static String output; 
	
	public static void main(String[] args) throws FileNotFoundException, IOException {
		Options options = getOptions(); 
		
		CommandLineParser parser = new DefaultParser();
		try {
		    CommandLine line = parser.parse(options, args);
			String pckge = normalizePackage(line.getOptionValue("p"));
			String app = line.getOptionValue("a");
			String o = line.getOptionValue("o");
			if(o!=null)
				output = o+File.separatorChar;
			else {
				logger.info("Output directory set to the current dir");
				output = "."+File.separatorChar;
			}
			if(app!=null)
				analyzeSingleApp(app, pckge);
			else {
				String dir = line.getOptionValue("d");
				analyzeDir(dir, pckge);
			}
		}
		catch(ParseException exp) {
		    logger.error("Parsing failed. Reason: " + exp.getMessage() );
		    HelpFormatter formatter = new HelpFormatter();
		    formatter.printHelp("LibUsageDetector", options );
		}
		
	}

	private static String normalizePackage(String packge) {
		return "L"+packge.replace(".", "/");
	}

	private static void analyzeDir(String dir, String pckge) {
		Arrays.stream(new File(dir).listFiles()).forEach(app -> {
			try {
				if(isFileTypeConsidered(Files.getFileExtension(app.getCanonicalPath())))
					analyzeSingleApp(app.getAbsolutePath(), pckge);
				else logger.info("Skipping file "+app);
			} catch (IOException e) {
				logger.error("Failed to process file "+app.getAbsolutePath());			}
		});
	}

	private static boolean isFileTypeConsidered(String fileExtension) {
		return fileExtension.equals("jar");
	}

	private static void analyzeSingleApp(String app, String pckge) throws IOException, FileNotFoundException {
		Collection<MethodGen> result = containsCallToPackage(app, pckge);
		if(result.isEmpty())
			logger.info("The app "+app+" does not contain any call to package "+pckge);
		else {
			logger.info("The app "+app+" contains calls to package "+pckge);
			String logfile = getLoggerFileName(app, pckge);
			if(! dumpToFile(result, logfile))
				System.err.println("Impossibile to dump the results of "+app);
		}
	}

	private static Options getOptions() {
		Options options = new Options();
		OptionGroup group = new OptionGroup();
		group.setRequired(true);
		group.addOption(new Option("a", "application", true, "the application to be checked"));
		group.addOption(new Option("d", "directory", true, "the directory containing the applications to be checked"));
		options.addOptionGroup(group);
		options.addRequiredOption("p", "package", true, "the package of the method calls we want to check");
		options.addOption("o", "output", true, "the directory where the output will be dumped");
		return options;
	}

	private static String getLoggerFileName(String app, String pckge) {
		return app+"_"+pckge.substring(1).replace("/", ".")+".log";
	}

	private static boolean dumpToFile(Collection<MethodGen> result, String app) {
		File logFile = new File(output+Files.getNameWithoutExtension(app)+"."+Files.getFileExtension(app));
        try(FileWriter fw = new FileWriter(logFile);
        		BufferedWriter writer = new BufferedWriter(fw))
        {
			for(MethodGen mg : result)
				writer.write(mg.getClassName()+"."+mg.getName()+mg.getSignature()+"\n");
			logger.info("Results dumped to "+logFile.getCanonicalPath());
			return true;
        } catch (IOException e) {
			logger.error("Cannot write to file "+logFile);
			return false;
		}
	}

	private static Collection<MethodGen> containsCallToPackage(String file, String packge) throws IOException, FileNotFoundException {
		Set<ClassGen> classes = extractClasses(file);
		Collection<MethodGen> result = new HashSet<MethodGen>();
		classes.stream()
			.forEach(cg -> {
					Arrays.stream(cg.getMethods())
					.forEach(m -> {
							ConstantPoolGen cpg = new ConstantPoolGen(m.getConstantPool());
							MethodGen mg = new MethodGen(m, cg.getClassName(), cpg);
							if(mg.getInstructionList()!=null) {
								Stream<String> filter = Arrays.stream(mg.getInstructionList().getInstructions())
									.filter(t -> (t instanceof InvokeInstruction))
									.map(t -> ((InvokeInstruction) t).getReferenceType(cpg).getSignature())
									.filter(s -> s.startsWith(packge));
								if(filter.findAny().isPresent())
									result.add(mg);
							}
						}
					);
				});
		return result;
	}

	private static Set<ClassGen> extractClasses(String file) throws IOException, FileNotFoundException {
		Set<ClassGen> classes = new HashSet<ClassGen>();
		
		try (FileInputStream in = new FileInputStream(file); JarInputStream jf = new JarInputStream(in)) {
			JarEntry entry = null;
			while ((entry = jf.getNextJarEntry()) != null) {
				if (entry.isDirectory())
					continue;
				String fileName = entry.getName();
				if (fileName.endsWith(".class")) {
					try (InputStream is = new ByteArrayInputStream(getBytes(jf))) {
						classes.add(new ClassGen(new ClassParser(is, fileName).parse()));
					}
					catch (IOException e) {
						logger.error(e + " while parsing " + fileName + " in " + file);
					}
				}
			}
		}
		return classes;
	}
	
	private static byte[] getBytes(InputStream is) throws IOException {
        try (ByteArrayOutputStream os = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[1024];
            int len;
            while ((len = is.read(buffer)) != -1)
                os.write(buffer, 0, len);

            os.flush();
            return os.toByteArray();
        }
    }


}
