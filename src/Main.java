import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.ArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.HashMap;
import java.util.Map;

public class Main {

    private static final List<String> sourcePaths = new ArrayList<>();
    private static final String destinationPath = "\\\\Truenas\\ti\\vitor\\backup";
    private static WatchService watchService;
    private static final Map<WatchKey, Path> keyDirectoryMap = new HashMap<>();
    private static final int timeBetweenRuns = 10; // SECONDS

    private static final List<String> fileAndFolderThatCannotBeModified = new ArrayList<>();
    private static final LocalDateTime limitTimeForFileAndFolderReplace = LocalDateTime.now().minus(10, ChronoUnit.MINUTES); // 30 days

    public static void main(String[] args) throws IOException {
        // Adicione quantos diretórios quiser
        sourcePaths.add("C:\\Users\\Suporte TI\\Desktop\\backup test server data");
        sourcePaths.add("D:\\vitor\\Gepit images");
        sourcePaths.add("D:\\vitor\\ISOs");

        watchService = FileSystems.getDefault().newWatchService();

        // Registrar todas as pastas e armazenar a relação WatchKey -> Path
        for (String sourcePath : sourcePaths) {
            Path path = Paths.get(sourcePath);
            WatchKey key = path.register(
                    watchService,
                    StandardWatchEventKinds.ENTRY_CREATE,
                    StandardWatchEventKinds.ENTRY_MODIFY,
                    StandardWatchEventKinds.ENTRY_DELETE);
            keyDirectoryMap.put(key, path);  // Mapear a WatchKey para o diretório correspondente
        }

        // Criar um ScheduledExecutorService para executar a cada 1 minuto
        ScheduledExecutorService executor = Executors.newScheduledThreadPool(1);
        executor.scheduleAtFixedRate(Main::monitorEvents, 0, timeBetweenRuns, TimeUnit.SECONDS);

        // O programa vai rodar indefinidamente.
        try {
            Thread.sleep(Long.MAX_VALUE); // Mantém o programa em execução
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt(); // Lida com a interrupção do thread
        } finally {
            executor.shutdown(); // Fecha o executor ao final
        }
    }

    private static void monitorEvents() {
        try {
            WatchKey key;
            while ((key = watchService.poll()) != null) {
                Path sourceDirectory = keyDirectoryMap.get(key);  // Obter o diretório de origem correspondente
                for (WatchEvent<?> event : key.pollEvents()) {
                    if (event.context().toString().charAt(0) != '~') {
                        Path sourcePath = (Path) event.context();
                        Path fullSourcePath = sourceDirectory.resolve(sourcePath);
                        Path destinationFilePath = Paths.get(destinationPath, sourceDirectory.relativize(fullSourcePath).toString());

                        System.out.println("Event kind: " + event.kind() + ". File Affected: " + fullSourcePath + ".");
                        foldersAndFilesThatCannotBeReplaced();
                        if (fileAndFolderThatCannotBeModified.contains(sourceDirectory.relativize(fullSourcePath).toString())) {
                            String newFileName = "novo - " + fullSourcePath.getFileName().toString();
                            Path newDestinationFilePath = destinationFilePath.resolveSibling(newFileName);
                            try {
                                Files.copy(fullSourcePath, newDestinationFilePath, StandardCopyOption.COPY_ATTRIBUTES);
                                System.out.println("File copied to: " + newDestinationFilePath);
                            } catch (IOException e) {
                                System.out.println("Erro ao copiar o arquivo: " + e.getMessage());
                            }
                        } else if (event.kind() == StandardWatchEventKinds.ENTRY_MODIFY) {
                            try {
                                Files.copy(fullSourcePath, destinationFilePath, StandardCopyOption.REPLACE_EXISTING);
                                System.out.println("File copied to: " + destinationFilePath);
                            } catch (IOException e) {
                                System.out.println("Erro ao copiar o arquivo: " + e.getMessage());
                            }
                        }
                    }
                }
                key.reset();
            }
        } catch (Exception e) {
            System.out.println("Erro ao monitorar eventos: " + e.getMessage());
        }
    }


    private static void foldersAndFilesThatCannotBeReplaced(){
        try {
            // Listando arquivos e pastas na pasta
            Files.walk(Path.of(destinationPath)).forEach(path -> {
                try {
                    // Obtém a data de modificação (funciona para arquivos e pastas)
                    BasicFileAttributes attr = Files.readAttributes(path, BasicFileAttributes.class);
                    LocalDateTime fileModifiedTime = attr.lastModifiedTime()
                            .toInstant().atZone(ZoneId.systemDefault()).toLocalDateTime();

                    // Verifica se o arquivo ou pasta foi modificado há mais de 30 dias
                    if (fileModifiedTime.isBefore(limitTimeForFileAndFolderReplace)) {
                        if (Files.isDirectory(path)) {
                            fileAndFolderThatCannotBeModified.add(String.valueOf(path.getFileName()));
                        } else if (Files.isRegularFile(path)) {
                            fileAndFolderThatCannotBeModified.add(String.valueOf(path.getFileName()));
                        }
                    }
                } catch (IOException e) {
                    e.printStackTrace();
                }
            });
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}
