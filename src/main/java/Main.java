import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Scanner;
import java.util.Set;

public class Main {
    private static final Set<String> BUILTINS = new HashSet<>(Arrays.asList("echo", "exit", "type", "pwd", "cd"));
    private static String currentDirectory = System.getProperty("user.dir");

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

            if (input.isEmpty()) {
                continue;
            }

            String[] parts = input.split("\\s+");
            String command = parts[0];

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
                System.out.println(sb.toString());
                continue;
            }

            if (command.equals("type")) {
                if (parts.length > 1) {
                    String target = parts[1];
                    if (BUILTINS.contains(target)) {
                        System.out.println(target + " is a shell builtin");
                    } else {
                        String executable = findExecutable(target);
                        if (executable != null) {
                            System.out.println(target + " is " + executable);
                        } else {
                            System.out.println(target + ": not found");
                        }
                    }
                }
                continue;
            }

            if (command.equals("pwd")) {
                System.out.println(currentDirectory);
                continue;
            }

            if (command.equals("cd")) {
                if (parts.length > 1) {
                    String target = parts[1];
                    if (target.startsWith("/")) {
                        File dir = new File(target);
                        if (dir.isDirectory()) {
                            try {
                                currentDirectory = dir.getCanonicalPath();
                            } catch (IOException e) {
                                currentDirectory = dir.getPath();
                            }
                        } else {
                            System.out.println("cd: " + target + ": No such file or directory");
                        }
                    } else {
                        // Relative paths and ~ are handled in a later stage.
                        System.out.println("cd: " + target + ": No such file or directory");
                    }
                }
                continue;
            }

            if (findExecutable(command) != null) {
                try {
                    ProcessBuilder pb = new ProcessBuilder(parts);
                    pb.directory(new File(currentDirectory));
                    pb.inheritIO();
                    Process process = pb.start();
                    process.waitFor();
                } catch (IOException | InterruptedException e) {
                    System.out.println(command + ": command not found");
                }
                continue;
            }

            // For now, every other command is treated as invalid.
            System.out.println(input + ": command not found");
        }
    }
}