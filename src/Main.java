import java.io.*;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.*;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import static java.nio.file.StandardWatchEventKinds.ENTRY_CREATE;

public class Main {

    private static final List<String> sourcePaths = new ArrayList<>();
    private static final List<String> subSourcePaths = new ArrayList<>();

    private static final String baseDestinationPath = "\\\\Truenas\\ti\\vitor\\backup";

    private static WatchService watchService;
    private static final Map<WatchKey, Path> keyDirectoryMap = new HashMap<>();
    private static final int timeBetweenRuns = 10; // SECONDS

    private static final List<Path> fileThatCannotBeModified = new ArrayList<>();
    private static final LocalDateTime limitTimeForFileAndFolderReplace = LocalDateTime.now().minusDays(30); // 30 days
    private static final int limitTimeForFileAndFolderReplaceInDays = 30; // days

    public static void main(String[] args) throws IOException {
        if (!verifyArquivePathControlExists()){
            createArquivePathControl();
        }

        // Adicione quantos diretórios quiser
        sourcePaths.add("C:\\Users\\Suporte TI\\Desktop\\backup test server data");
        //sourcePaths.add("D:\\vitor\\Gepit images");
        //sourcePaths.add("D:\\vitor\\ISOs");

        for(int i = 0; i < sourcePaths.size(); i++) {
            Path actualPath = Paths.get(sourcePaths.get(i));

            try {
                // Lista os elementos (subpastas) dentro do diretório
                List<String> subdirectories = Files.list(actualPath)
                        .filter(Files::isDirectory) // Filtra apenas subpastas
                        .map(Path::toString) // Converte Path para String
                        .toList(); // Filtra para pegar apenas subpastas
                subSourcePaths.addAll(subdirectories);
            } catch (IOException e) {
                System.out.println("Erro ao adicionar os source paths" + e);
            }
        }

        watchService = FileSystems.getDefault().newWatchService();

        // Registrar todas as pastas e armazenar a relação WatchKey -> Path
        for (String sourcePath : sourcePaths) {
            Path path = Paths.get(sourcePath);
            addArquiveToWatcher(path);
        }

        // Adiciona os subarquivos
        for (String subSourcePaths : subSourcePaths) {
            Path path = Paths.get(subSourcePaths);
            addArquiveToWatcher(path);
        }

        // Adiciona os arquivos que não podem ser modificados.
        addFilesThatCannotBeModified();

        if(verfiyIfFirstBackup()){
            try{
                performFistBackup();
            } catch (Exception e){
                System.out.println("Primeiro backup falhou " + e);
            }

        }

        // Criar um ScheduledExecutorService para executar a cada 1 minuto
        ScheduledExecutorService executor = Executors.newScheduledThreadPool(1);
        executor.scheduleAtFixedRate(Main::monitorEvents, 0, timeBetweenRuns, TimeUnit.SECONDS);

        // O programa vai rodar indefinidamente.
        try {
            Thread.sleep(Long.MAX_VALUE); // Mantém o programa em execução
        } catch (InterruptedException e) {
            System.out.println("Erro de thread " + e);
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
                            System.out.println("error monitor events HandlePathCopy " + e);
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
                Path destination = Path.of(baseDestinationPath + "\\" + fullSourcePath.getFileName());
                if(event.kind() == ENTRY_CREATE){
                    try {
                        if(Files.isDirectory(fullSourcePath)){
                            try{
                                copyRecursive(fullSourcePath, destination);
                                recursiveAddArquiveToWatcher(fullSourcePath);
                            } catch (Exception e ){
                                System.out.println("Erro no recursive add " + e);
                            }
                        } else if(checkIfCanUpdateFile(fullSourcePath)){
                            System.out.println(fullSourcePath + "+++" + destination);
                            Instant timeStamp = Instant.now();
                            addSourcePathControlArquive(fullSourcePath, destination, timeStamp);
                            Files.copy(fullSourcePath, destination, StandardCopyOption.REPLACE_EXISTING);
                        } else if(!checkIfCanUpdateFile(fullSourcePath)){
                            Path newDestination = Path.of(destination.getParent() + "\\Novo - " + relativePath.replace("\\", ""));
                            Files.copy(fullSourcePath, newDestination, StandardCopyOption.REPLACE_EXISTING);
                        }

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
              if(checkIfCanUpdateFile(source)){
                  Instant timeStamp = Instant.now();
                  Path destinationFile = destination.resolve(source.relativize(file));
                  addSourcePathControlArquive(file, destinationFile, timeStamp);
                  Files.copy(file,destinationFile, StandardCopyOption.REPLACE_EXISTING);
              } else{
                  String relativePath = String.valueOf(source.relativize(file));
                  Path newDestination = Path.of(destination.getParent() + "\\Novo - " + relativePath.replace("\\", ""));
                  Files.copy(file,newDestination, StandardCopyOption.REPLACE_EXISTING);
              }

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


    private static void addFilesThatCannotBeModified(){
        try {
            // Listando arquivos e pastas na pasta
            Files.walk(Path.of(baseDestinationPath)).forEach(path -> {
                try {
                    // Obtém a data de modificação (funciona para arquivos e pastas)
                    BasicFileAttributes attr = Files.readAttributes(path, BasicFileAttributes.class);
                    LocalDateTime fileModifiedTime = attr.lastModifiedTime()
                            .toInstant().atZone(ZoneId.systemDefault()).toLocalDateTime();

                    // Verifica se o arquivo ou pasta foi modificado há mais de 30 dias
                    if (fileModifiedTime.isBefore(limitTimeForFileAndFolderReplace) && Files.isRegularFile(path)) {
                        fileThatCannotBeModified.add(path);
                    }
                } catch (IOException e) {
                    System.out.println("Error 1 addFilesThatCannotBeModified " + e);
                }
            });
        } catch (IOException e) {
            System.out.println("Error 2 addFilesThatCannotBeModified " + e);
        }
    }

    private static void createArquivePathControl(){
        try{
            File pathControlFile = new File("PathControl.txt");

            if(pathControlFile.createNewFile()){
                System.out.println("Arquivo criado com sucesso");
            } else {
                System.out.println("Arquivo já existe");
            }
        } catch (Exception e){
            System.out.println("createPathControlArquive error " + e);
        }
    }

    private static boolean verifyArquivePathControlExists(){
        File pathControlFile = new File("PathControl.txt");

        if(pathControlFile.exists()){
            return true;
        }
        return false;
    }

    private static void addSourcePathControlArquive(Path sourcePath, Path destinationPath, Instant timestamp){

        try{

            BufferedReader reader = new BufferedReader(new FileReader("PathControl.txt"));
            String line;
            boolean arquivoEstaNoPathControl = false;
            while((line = reader.readLine()) != null){
                String[] params = line.split("\\|");
                if(params.length > 0 && params[0].equals(sourcePath.toString())){
                    arquivoEstaNoPathControl = true;
                }

            }

            if(!arquivoEstaNoPathControl){
                File file = new File("PathControl.txt");

                FileWriter fileWriter = new FileWriter(file, true);
                PrintWriter printWriter = new PrintWriter(fileWriter);

                printWriter.println(sourcePath + "|" + destinationPath + "|" + timestamp);
                printWriter.close();
            } else{
                modifyTimestampFromSourcePathControlArquive(sourcePath, timestamp);
            }

        } catch (Exception e){
            System.out.println("AddSourcePathToControlArquive error: " + e);
        }

    }

    private static void modifyTimestampFromSourcePathControlArquive(Path searchPath, Instant timestamp){
        String arquiveName = "PathControl.txt";

        File file = new File(arquiveName);

        String[] oldLineParams = null;

        List<String> lines = new ArrayList<>();

        try {
            BufferedReader reader = new BufferedReader(new FileReader(arquiveName));
            String line;
            while((line = reader.readLine()) !=null){
                String[] params = line.split("\\|");
                if(params.length > 0  && params[0].equals(searchPath.toString())){

                    line = searchPath + "|" + params[1] + "|" + timestamp;
                }

                lines.add(line);
            }

            reader.close();

        } catch (Exception e){
            System.out.println("Read - ModifyTimestampFromSourcePathControlArquive erro: " + e);
        }

        try {
            PrintWriter writer = new PrintWriter(new FileWriter(arquiveName));

            for(String line : lines){
                writer.println(line);
            }

            writer.close();
            System.out.println("arquivo atualizado com sucesso");
        } catch (Exception e) {
            System.out.println("Write - ModifyTimestampFromSourcePathControlArquive erro: " + e);
        }
    }

    private static boolean checkIfCanUpdateFile(Path sourcePath) throws IOException {
        try{
             BufferedReader reader = new BufferedReader(new FileReader("PathControl.txt"));
             String line;
            while((line = reader.readLine()) != null){
                String[] params = line.split("\\|");
                if(params.length > 0 && params[0].equals(sourcePath.toString())){
                    Instant actualFileTime = Files.getLastModifiedTime(sourcePath).toInstant();
                    Instant lastModificationInstant = Instant.parse(params[2]);

                    long daysBetween = Duration.between(lastModificationInstant, actualFileTime).toMinutes();

                    if(daysBetween >= limitTimeForFileAndFolderReplaceInDays){
                        return false;
                    } else{
                        return true;
                    }
                }
            }

        } catch (Exception e){
            System.out.println("Erro no checkIfCanUpdateFile " + e);
        }
        return true; //mudar para false
    }

    private static boolean verfiyIfFirstBackup() {
        try {
            File firstBackupFile = new File("firstBackup.txt");

            // Verifica se o arquivo já existe
            if (firstBackupFile.exists()) {
                System.out.println("Arquivo firstBackup.txt já existe");
                return false; // Indica que o arquivo já existia
            } else {
                // Cria o arquivo se ele não existir
                if (firstBackupFile.createNewFile()) {
                    System.out.println("Arquivo firstBackup.txt criado com sucesso");
                    return true; // Indica que o arquivo foi criado com sucesso
                } else {
                    System.out.println("Falha ao criar o arquivo firstBackup.txt");
                }
            }
        } catch (Exception e) {
            System.out.println("Erro ao verificar/criar o first backup: " + e.getMessage());
        }
        return false;
    }

    private static void performFistBackup() throws Exception{

        File firstBackupFile = new File("firstBackup.txt");
        if(!firstBackupFile.exists()){
            firstBackupFile.createNewFile();
        }


        for (int i = 0; i < sourcePaths.size(); i++) {
            Path sourcePath = Path.of(sourcePaths.get(i));
            Path destinationPath = Path.of(baseDestinationPath + "\\" + sourcePath.getFileName().toString());

            try {
                copyRecursive(sourcePath, destinationPath);
                // Adicionar as pastas e arquivos ao watcher para monitoramento após o backup inicial
                recursiveAddArquiveToWatcher(sourcePath);
            } catch (Exception e) {
                System.out.println("Erro ao realizar backup inicial: " + e);
            }
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

    private static void printListFileThatCannotBeModified(){
        System.out.println("FILES THAT CANNOT BE MODIFIED");

        for(int i = 0; i < fileThatCannotBeModified.size(); i++){
            System.out.println(fileThatCannotBeModified.get(i));
        }

        System.out.println("--------------");
    }

}