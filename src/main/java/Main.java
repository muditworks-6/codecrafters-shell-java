import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Scanner;
import java.util.Set;

public class Main {
    private static final Set<String> BUILTINS = new HashSet<>(Arrays.asList("echo", "exit", "type", "pwd", "cd"));
    private static String currentDirectory = System.getProperty("user.dir");

    private static boolean isEscapableInDoubleQuotes(char c) {
        return c == '"' || c == '\\' || c == '$' || c == '`' || c == '\n';
    }

    private static void touchFile(File file, boolean append) {
        try (FileOutputStream fos = new FileOutputStream(file, append)) {
            // Opening (and immediately closing) creates the file if it's missing.
            // In append mode this leaves any existing content untouched; in
            // overwrite mode it truncates it - matching how a shell opens the
            // redirect target before the command even runs.
        } catch (IOException e) {
            System.err.println(file.getPath() + ": " + e.getMessage());
        }
    }

    private static void printLine(String line, File outFile, boolean append) {
        if (outFile == null) {
            System.out.println(line);
            return;
        }

        try (PrintStream out = new PrintStream(new FileOutputStream(outFile, append))) {
            out.println(line);
        } catch (IOException e) {
            System.err.println(outFile.getPath() + ": " + e.getMessage());
        }
    }

    private static void printErrLine(String line, File errFile, boolean append) {
        if (errFile == null) {
            System.err.println(line);
            return;
        }

        try (PrintStream out = new PrintStream(new FileOutputStream(errFile, append))) {
            out.println(line);
        } catch (IOException e) {
            System.err.println(errFile.getPath() + ": " + e.getMessage());
        }
    }

    private static List<String> tokenize(String input) {
        List<String> tokens = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inSingleQuote = false;
        boolean inDoubleQuote = false;
        boolean hasToken = false;

        for (int i = 0; i < input.length(); i++) {
            char c = input.charAt(i);

            if (inSingleQuote) {
                if (c == '\'') {
                    inSingleQuote = false;
                } else {
                    current.append(c);
                }
                continue;
            }

            if (inDoubleQuote) {
                if (c == '"') {
                    inDoubleQuote = false;
                } else if (c == '\\' && i + 1 < input.length() && isEscapableInDoubleQuotes(input.charAt(i + 1))) {
                    i++;
                    current.append(input.charAt(i));
                } else {
                    current.append(c);
                }
                continue;
            }

            if (c == '\\') {
                if (i + 1 < input.length()) {
                    i++;
                    current.append(input.charAt(i));
                    hasToken = true;
                }
                continue;
            }

            if (c == '\'') {
                inSingleQuote = true;
                hasToken = true;
                continue;
            }

            if (c == '"') {
                inDoubleQuote = true;
                hasToken = true;
                continue;
            }

            if (Character.isWhitespace(c)) {
                if (hasToken) {
                    tokens.add(current.toString());
                    current.setLength(0);
                    hasToken = false;
                }
                continue;
            }

            current.append(c);
            hasToken = true;
        }

        if (hasToken) {
            tokens.add(current.toString());
        }

        return tokens;
    }

    private static String findExecutable(String command) {
        String path = System.getenv("PATH");
        if (path == null) {
            return null;
        }

        for (String dir : path.split(File.pathSeparator)) {
            File file = new File(dir, command);
            if (file.isFile() && file.canExecute()) {
                return file.getPath();
            }
        }
        return null;
    }

    // Splits the token list on "|" into one list of tokens per pipeline stage.
    private static List<List<String>> splitPipeline(List<String> tokens) {
        List<List<String>> segments = new ArrayList<>();
        List<String> current = new ArrayList<>();
        for (String t : tokens) {
            if (t.equals("|")) {
                segments.add(current);
                current = new ArrayList<>();
            } else {
                current.add(t);
            }
        }
        segments.add(current);
        return segments;
    }

    // Runs a pipeline of two or more external commands, connecting each
    // stage's stdout to the next stage's stdin. Only external executables
    // are supported as pipeline stages at this point - builtins aren't.
    private static void runPipeline(List<String> tokens) {
        List<List<String>> segments = splitPipeline(tokens);
        List<ProcessBuilder> builders = new ArrayList<>();

        for (List<String> seg : segments) {
            if (seg.isEmpty()) {
                System.err.println("syntax error near unexpected token `|'");
                return;
            }

            String cmd = seg.get(0);
            if (findExecutable(cmd) == null) {
                System.err.println(cmd + ": command not found");
                return;
            }

            ProcessBuilder pb = new ProcessBuilder(seg);
            pb.directory(new File(currentDirectory));
            pb.redirectError(ProcessBuilder.Redirect.INHERIT);
            builders.add(pb);
        }

        // The first stage reads from the terminal, the last stage writes to
        // it; startPipeline wires up the pipes between every stage in between.
        builders.get(0).redirectInput(ProcessBuilder.Redirect.INHERIT);
        builders.get(builders.size() - 1).redirectOutput(ProcessBuilder.Redirect.INHERIT);

        try {
            List<Process> processes = ProcessBuilder.startPipeline(builders);
            for (Process p : processes) {
                p.waitFor();
            }
        } catch (IOException | InterruptedException e) {
            System.err.println("pipeline: " + e.getMessage());
        }
    }

    public static void main(String[] args) {
        Scanner scanner = new Scanner(System.in);

        while (true) {
            System.out.print("$ ");

            if (!scanner.hasNextLine()) {
                break;
            }

            String input = scanner.nextLine();

            List<String> tokens = tokenize(input);
            if (tokens.isEmpty()) {
                continue;
            }

            if (tokens.contains("|")) {
                runPipeline(tokens);
                continue;
            }

            // Extract stdout redirection (>, 1>, >>, 1>>), tracking whether
            // it should append rather than overwrite.
            String outputTarget = null;
            boolean appendOutput = false;
            for (int idx = 0; idx < tokens.size(); idx++) {
                String t = tokens.get(idx);
                if (idx + 1 >= tokens.size()) {
                    continue;
                }
                if (t.equals(">>") || t.equals("1>>")) {
                    outputTarget = tokens.get(idx + 1);
                    appendOutput = true;
                    tokens.remove(idx + 1);
                    tokens.remove(idx);
                    break;
                }
                if (t.equals(">") || t.equals("1>")) {
                    outputTarget = tokens.get(idx + 1);
                    appendOutput = false;
                    tokens.remove(idx + 1);
                    tokens.remove(idx);
                    break;
                }
            }

            // Extract stderr redirection (2>, 2>>), tracking append vs overwrite.
            String errorTarget = null;
            boolean appendError = false;
            for (int idx = 0; idx < tokens.size(); idx++) {
                String t = tokens.get(idx);
                if (idx + 1 >= tokens.size()) {
                    continue;
                }
                if (t.equals("2>>")) {
                    errorTarget = tokens.get(idx + 1);
                    appendError = true;
                    tokens.remove(idx + 1);
                    tokens.remove(idx);
                    break;
                }
                if (t.equals("2>")) {
                    errorTarget = tokens.get(idx + 1);
                    appendError = false;
                    tokens.remove(idx + 1);
                    tokens.remove(idx);
                    break;
                }
            }

            if (tokens.isEmpty()) {
                continue;
            }

            String[] parts = tokens.toArray(new String[0]);
            String command = parts[0];

            File outFile = null;
            if (outputTarget != null) {
                outFile = outputTarget.startsWith("/")
                        ? new File(outputTarget)
                        : new File(currentDirectory, outputTarget);
                touchFile(outFile, appendOutput);
            }

            File errFile = null;
            if (errorTarget != null) {
                errFile = errorTarget.startsWith("/")
                        ? new File(errorTarget)
                        : new File(currentDirectory, errorTarget);
                touchFile(errFile, appendError);
            }

            if (command.equals("exit")) {
                int exitCode = 0;
                if (parts.length > 1) {
                    try {
                        exitCode = Integer.parseInt(parts[1]);
                    } catch (NumberFormatException e) {
                        exitCode = 0;
                    }
                }
                System.exit(exitCode);
            }

            if (command.equals("echo")) {
                StringBuilder sb = new StringBuilder();
                for (int i = 1; i < parts.length; i++) {
                    if (i > 1) {
                        sb.append(" ");
                    }
                    sb.append(parts[i]);
                }
                printLine(sb.toString(), outFile, appendOutput);
                continue;
            }

            if (command.equals("type")) {
                if (parts.length > 1) {
                    String target = parts[1];
                    if (BUILTINS.contains(target)) {
                        printLine(target + " is a shell builtin", outFile, appendOutput);
                    } else {
                        String executable = findExecutable(target);
                        if (executable != null) {
                            printLine(target + " is " + executable, outFile, appendOutput);
                        } else {
                            printErrLine(target + ": not found", errFile, appendError);
                        }
                    }
                }
                continue;
            }

            if (command.equals("pwd")) {
                printLine(currentDirectory, outFile, appendOutput);
                continue;
            }

            if (command.equals("cd")) {
                if (parts.length > 1) {
                    String target = parts[1];

                    if (target.equals("~")) {
                        String home = System.getenv("HOME");
                        target = (home != null) ? home : target;
                    } else if (target.startsWith("~/")) {
                        String home = System.getenv("HOME");
                        if (home != null) {
                            target = home + target.substring(1);
                        }
                    }

                    File dir = target.startsWith("/")
                            ? new File(target)
                            : new File(currentDirectory, target);

                    if (dir.isDirectory()) {
                        try {
                            currentDirectory = dir.getCanonicalPath();
                        } catch (IOException e) {
                            currentDirectory = dir.getPath();
                        }
                    } else {
                        printErrLine("cd: " + target + ": No such file or directory", errFile, appendError);
                    }
                }
                continue;
            }

            if (findExecutable(command) != null) {
                try {
                    ProcessBuilder pb = new ProcessBuilder(parts);
                    pb.directory(new File(currentDirectory));
                    pb.redirectInput(ProcessBuilder.Redirect.INHERIT);

                    if (outFile != null) {
                        pb.redirectOutput(appendOutput
                                ? ProcessBuilder.Redirect.appendTo(outFile)
                                : ProcessBuilder.Redirect.to(outFile));
                    } else {
                        pb.redirectOutput(ProcessBuilder.Redirect.INHERIT);
                    }

                    if (errFile != null) {
                        pb.redirectError(appendError
                                ? ProcessBuilder.Redirect.appendTo(errFile)
                                : ProcessBuilder.Redirect.to(errFile));
                    } else {
                        pb.redirectError(ProcessBuilder.Redirect.INHERIT);
                    }

                    Process process = pb.start();
                    process.waitFor();
                } catch (IOException | InterruptedException e) {
                    printErrLine(command + ": command not found", errFile, appendError);
                }
                continue;
            }

            // For now, every other command is treated as invalid.
            printErrLine(command + ": command not found", errFile, appendError);
        }
    }
}