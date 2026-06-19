import java.util.Scanner;

public class Main {
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
 
            // For now, every command is treated as invalid.
            System.out.println(input + ": command not found");
        }
    }
}
