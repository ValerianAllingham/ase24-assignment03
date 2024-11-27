import java.io.*;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Collection;
import java.util.List;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.Random;
import java.util.Arrays;
import java.util.ArrayList;

public class Fuzzer {
    public static void main(String[] args) {
        if (args.length != 1) {
            System.err.println("Usage: java Fuzzer.java \"<command_to_fuzz>\"");
            System.exit(1);
        }
        String commandToFuzz = args[0];
        String workingDirectory = "./";

        if (!Files.exists(Paths.get(workingDirectory, commandToFuzz))) {
            throw new RuntimeException("Could not find command '%s'.".formatted(commandToFuzz));
        }

        String seedInput = "<html a=\"value\">...</html>";

        ProcessBuilder builder = getProcessBuilderForCommand(commandToFuzz, workingDirectory);
        System.out.printf("Command: %s\n", builder.command());

        
        

        runCommand(builder, seedInput, getMutatedInputs(seedInput, List.of(
            input -> randomizeContent(input)

        )));
        System.out.printf("Everything went right, exiting with exit code 0\n");
        System.exit(0);
        
    }

    private static String randomizeContent(String token) {
        Random random = new Random();

        // Randomly choose a mutation
        switch (random.nextInt(15)) { // Choose between 5 mutation strategies
            case 0: // Replace some characters with random ones
                return token.replace("a", getFuzzInput(1, 'b', 25)); // Replace 'a' with random letter
            case 1: // Add random characters to the token
                return token.replace(token, getFuzzInput(65, 'a', 26)); // Replace token with random characters
            case 2: // Reverse the token
                return new StringBuilder(token).reverse().toString();
            case 4: // delete whole token
                return token.replace(token, "");
            case 5: // Give value two pairs of "" 
                return token.replace("value", "\"value\"");
            case 6:
                return token.replace("<html", "<" + getFuzzInput(17, 'a', 26));
            case 7: // Insert a random character
                int position0 = random.nextInt(token.length() + 1);
                char randomChar = (char) (random.nextInt(94) + 33); // Random printable ASCII character
                return token.substring(0, position0) + randomChar + token.substring(position0);

            case 8: // Insert a character already in the string
                if (!token.isEmpty()) {
                    int position1 = random.nextInt(token.length() + 1);
                    char charFromToken = token.charAt(random.nextInt(token.length()));
                    return token.substring(0, position1) + charFromToken + token.substring(position1);
                }
                return token;

            case 9: // Repeat a character
                if (!token.isEmpty()) {
                    int position2 = random.nextInt(token.length());
                    char charToRepeat = token.charAt(position2);
                    return token.substring(0, position2) + charToRepeat + charToRepeat + token.substring(position2 + 1);
                }
                return token;

            case 10: // Delete a character
                if (!token.isEmpty()) {
                    int position3 = random.nextInt(token.length());
                    return token.substring(0, position3) + token.substring(position3 + 1);
                }
                return token;

            case 11: // Swap two characters
                if (token.length() > 1) {
                    int pos1 = random.nextInt(token.length());
                    int pos2 = random.nextInt(token.length());
                    char[] chars = token.toCharArray();
                    char temp = chars[pos1];
                    chars[pos1] = chars[pos2];
                    chars[pos2] = temp;
                    return new String(chars);
                }
            case 12:
                return token.replace("</html>", ">>");
            case 13:
                return token.replace("a", getFuzzInput(9, 'a', 26));
            case 14:
                return token + new Random().ints(10, 32, 127)
                               .mapToObj(i -> String.valueOf((char) i))
                               .collect(Collectors.joining());
            default: 
                return token;
        }
    }

    private static String getFuzzInput(int maxLength, char charStart, int charRange) {
        Random random = new Random();
        int length = random.nextInt(maxLength) + 1; // length in [1, maxLength]
        // start with sequence of integers representing random characters in the configured range
        return random.ints(length, charStart, charStart + charRange)
                .mapToObj(Character::toChars) // int to char
                .map(String::valueOf) // char to string
                .collect(Collectors.joining());
    }

    private static List<String> tokenizeHtml(String htmlInput) {
        return Arrays.asList(htmlInput.split("(?=<)|(?<=>)")); // Convert array to List
    }

    private static String mutateHtml(String htmlInput, List<Function<String, String>> mutators) {
        List<String> tokens = tokenizeHtml(htmlInput);
        Random random = new Random();

        boolean mutated = false; // Ensure at least one mutation
        for (int i = 0; i < tokens.size(); i++) {
            if (random.nextDouble() < 0.7 || !mutated) { // Higher chance of mutation
                Function<String, String> mutator = mutators.get(random.nextInt(mutators.size()));
                tokens.set(i, mutator.apply(tokens.get(i)));
                mutated = true;
            }
        }
        return String.join("", tokens);
    }





    private static ProcessBuilder getProcessBuilderForCommand(String command, String workingDirectory) {
        ProcessBuilder builder = new ProcessBuilder();
        boolean isWindows = System.getProperty("os.name").toLowerCase().startsWith("windows");
        if (isWindows) {
            builder.command("cmd.exe", "/c", command);
        } else {
            builder.command("sh", "-c", command);
        }
        builder.directory(new File(workingDirectory));
        builder.redirectErrorStream(false); // redirect stderr to stdout
        return builder;
    }

    private static void runCommand(ProcessBuilder builder, String seedInput, List<String> mutatedInputs) {
        Stream.concat(Stream.of(seedInput), mutatedInputs.stream()).forEach(input -> {
            try {
                // Prepare process
                Process process = builder.start();

                // Write the input to the process's stdin
                try (BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(process.getOutputStream()))) {
                    writer.write(input);
                    writer.flush();
                    writer.close();
                }

                // Capture the process output
                String output = readStreamIntoString(process.getInputStream());
                String errorOutput = readStreamIntoString(process.getErrorStream());
                // Wait for the process to finish
                int exitCode = process.waitFor();
                 if (!(exitCode == 0)){
                     System.out.printf("Non-zero exit code: Exiting with exit Code %d\n", exitCode);
                     System.exit(1);
                 }
                // Print results
                System.out.printf("Input: %s\nExit Code: %d\nStdout:\n%s\nStderr:\n%s\n", input, exitCode, output, errorOutput);
                

            } catch (IOException | InterruptedException e) {
                // Handle exceptions that occur during execution
                System.err.printf("An error occurred while executing the command with input: %s\n", input);
                e.printStackTrace();
            }
            
    });
    }

    private static String readStreamIntoString(InputStream inputStream) {
        BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream));
        return reader.lines()
                .map(line -> line + System.lineSeparator())
                .collect(Collectors.joining());
    }

    private static List<String> getMutatedInputs(String seedInput, Collection<Function<String, String>> mutators) {
        List<String> mutatedInputs = new ArrayList<>();
        for (int i = 0; i < 10; i++) { // Generate 10 mutated inputs
            mutatedInputs.add(mutateHtml(seedInput, new ArrayList<>(mutators)));
            System.out.println("Mutated Input: " + mutatedInputs.get(mutatedInputs.size() - 1));
        }
        return mutatedInputs;
    }

        
}
