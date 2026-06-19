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

    private static void printLine(String line, File outFile) {
        if (outFile == null) {
            System.out.println(line);
            return;
        }

        try (PrintStream out = new PrintStream(new FileOutputStream(outFile))) {
            out.println(line);
        } catch (IOException e) {
            System.err.println(outFile.getPath() + ": " + e.getMessage());
        }
    }

    private static void printError(String line, File errFile) {
        if (errFile == null) {
            System.err.println(line);
            return;
        }

        try (PrintStream out = new PrintStream(new FileOutputStream(errFile))) {
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
                } else if (c == '\\' && i + 1 < input.length()
                        && isEscapableInDoubleQuotes(input.charAt(i + 1))) {
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

            String outputTarget = null;
            String errorTarget = null;

            for (int idx = 0; idx < tokens.size(); idx++) {
                String t = tokens.get(idx);

                if ((t.equals(">") || t.equals("1>")) && idx + 1 < tokens.size()) {
                    outputTarget = tokens.get(idx + 1);
                    tokens.remove(idx + 1);
                    tokens.remove(idx);
                    idx--;
                } else if (t.equals("2>") && idx + 1 < tokens.size()) {
                    errorTarget = tokens.get(idx + 1);
                    tokens.remove(idx + 1);
                    tokens.remove(idx);
                    idx--;
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
            }

            File errFile = null;
            if (errorTarget != null) {
                errFile = errorTarget.startsWith("/")
                        ? new File(errorTarget)
                        : new File(currentDirectory, errorTarget);
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

                printLine(sb.toString(), outFile);
                continue;
            }

            if (command.equals("type")) {
                if (parts.length > 1) {
                    String target = parts[1];

                    if (BUILTINS.contains(target)) {
                        printLine(target + " is a shell builtin", outFile);
                    } else {
                        String executable = findExecutable(target);

                        if (executable != null) {
                            printLine(target + " is " + executable, outFile);
                        } else {
                            printError(target + ": not found", errFile);
                        }
                    }
                }

                continue;
            }

            if (command.equals("pwd")) {
                printLine(currentDirectory, outFile);
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
                        printError("cd: " + target + ": No such file or directory", errFile);
                    }
                }

                continue;
            }

            if (findExecutable(command) != null) {
                try {
                    ProcessBuilder pb = new ProcessBuilder(parts);
                    pb.directory(new File(currentDirectory));

                    pb.redirectInput(ProcessBuilder.Redirect.INHERIT);

                    pb.redirectOutput(
                            outFile != null
                                    ? ProcessBuilder.Redirect.to(outFile)
                                    : ProcessBuilder.Redirect.INHERIT);

                    pb.redirectError(
                            errFile != null
                                    ? ProcessBuilder.Redirect.to(errFile)
                                    : ProcessBuilder.Redirect.INHERIT);

                    Process process = pb.start();
                    process.waitFor();
                } catch (IOException | InterruptedException e) {
                    printError(command + ": command not found", errFile);
                }

                continue;
            }

            printError(command + ": command not found", errFile);
        }
    }
}