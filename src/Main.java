import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static java.nio.file.StandardWatchEventKinds.ENTRY_CREATE;

public class Main {

    private static final List<String> sourcePaths = new ArrayList<>();
    private static final List<String> subSourcePaths = new ArrayList<>();

    private static final String baseDestinationPath = "\\\\Truenas\\ti\\vitor\\backup";

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

        for(int i = 0; i < sourcePaths.size(); i++) {
            Path actualPath = Paths.get(sourcePaths.get(i));

            try {
                // Lista os elementos (subpastas) dentro do diretório
                List<String> subdirectories = Files.list(actualPath)
                        .filter(Files::isDirectory) // Filtra apenas subpastas
                        .map(Path::toString) // Converte Path para String
                        .collect(Collectors.toList()); // Filtra para pegar apenas subpastas
                subSourcePaths.addAll(subdirectories);
            } catch (IOException e) {
                e.printStackTrace();
            }
        }

        watchService = FileSystems.getDefault().newWatchService();

        // Registrar todas as pastas e armazenar a relação WatchKey -> Path
        for (String sourcePath : sourcePaths) {
            Path path = Paths.get(sourcePath);
            addArquiveToWatcher(path); // Mapear a WatchKey para o diretório correspondente
        }

        for (String subSourcePaths : subSourcePaths) {
            Path path = Paths.get(subSourcePaths);
            addArquiveToWatcher(path);
            // Mapear a WatchKey para o diretório correspondente
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

                        try {
                            handlePathCopy(fullSourcePath, event);
                        } catch (Exception e) {
                            System.out.println(e);
                        }
                    }
                }
                key.reset();
            }
        } catch (Exception e) {
            System.out.println("Erro ao monitorar eventos: " + e.getMessage());
        }
    }

    private static void handlePathCopy(Path fullSourcePath, WatchEvent<?> event) throws Exception {
        for(int i = 0; i < sourcePaths.size(); i++){
            Path basePath = Path.of(sourcePaths.get(i));
            if(fullSourcePath.startsWith((basePath))){
                String relativePath = fullSourcePath.toString()
                        .substring(basePath.toString().length());
                Path destination = Path.of(baseDestinationPath + relativePath);
                if(event.kind() == ENTRY_CREATE){
                    try {
                        if(Files.isDirectory(fullSourcePath)){
                            copyRecursive(fullSourcePath, destination);
                            try{
                                recursiveAddArquiveToWatcher(fullSourcePath);
                            } catch (Exception e ){
                                System.out.println("Erro no recursive add " + e);
                            }
                        } else {
                            Files.copy(fullSourcePath, destination, StandardCopyOption.REPLACE_EXISTING);
                        }

                        //printKeyDirectoryMap();
                    } catch (Exception e) {
                        System.out.println("Erro handle copy: " + e);
                    }
                }

                Files.copy(fullSourcePath, destination, StandardCopyOption.REPLACE_EXISTING);
            }
        }
    }

    private static void copyRecursive(Path source, Path destination) throws IOException {
        if(Files.notExists(destination) && Files.isDirectory(source)){
            Files.createDirectories(destination);
        }

        Files.walkFileTree(source, new SimpleFileVisitor<Path>(){
          @Override
          public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
              Path destinationdDir = destination.resolve(source.relativize(dir));
              if(Files.notExists(destinationdDir) && Files.isDirectory(source)){
                  Files.createDirectories(destinationdDir);
              }
              return FileVisitResult.CONTINUE;
          }

          public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException{
              Path destinationFile = destination.resolve(source.relativize(file));
              Files.copy(file,destinationFile, StandardCopyOption.REPLACE_EXISTING);
              return FileVisitResult.CONTINUE;
          }

        });
    }

    private static void addArquiveToWatcher(Path path) throws IOException {
        WatchKey key = path.register(
                watchService,
                ENTRY_CREATE,
                StandardWatchEventKinds.ENTRY_MODIFY,
                StandardWatchEventKinds.ENTRY_DELETE);
        keyDirectoryMap.put(key, path);
    }

    private static void recursiveAddArquiveToWatcher(Path path) throws IOException {
        if (Files.isDirectory(path)) {
            // Adiciona o diretório atual ao Watcher
            WatchKey key = path.register(
                    watchService,
                    ENTRY_CREATE,
                    StandardWatchEventKinds.ENTRY_MODIFY,
                    StandardWatchEventKinds.ENTRY_DELETE);
            keyDirectoryMap.put(key, path);

            // Adiciona recursivamente todos os subdiretórios e arquivos ao Watcher
            Files.walkFileTree(path, new SimpleFileVisitor<Path>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                    WatchKey subDirKey = dir.register(
                            watchService,
                            ENTRY_CREATE,
                            StandardWatchEventKinds.ENTRY_MODIFY,
                            StandardWatchEventKinds.ENTRY_DELETE);
                    keyDirectoryMap.put(subDirKey, dir);
                    return FileVisitResult.CONTINUE;
                }
            });
        } else {
            // Adiciona apenas o arquivo se não for um diretório
            WatchKey key = path.register(
                    watchService,
                    ENTRY_CREATE,
                    StandardWatchEventKinds.ENTRY_MODIFY,
                    StandardWatchEventKinds.ENTRY_DELETE);
            keyDirectoryMap.put(key, path);
        }
    }


    private static void foldersAndFilesThatCannotBeReplaced(){
        try {
            // Listando arquivos e pastas na pasta
            Files.walk(Path.of(baseDestinationPath)).forEach(path -> {
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

    private static void printKeyDirectoryMap(){
        System.out.println("KEY DIRECTORY MAP:");
        Collection<Path> keyValues = keyDirectoryMap.values();
        Iterator<Path> iterator = keyValues.iterator();
        while(iterator.hasNext()){
            System.out.println(iterator.next());
        }


        System.out.println("--------------");
    }

}