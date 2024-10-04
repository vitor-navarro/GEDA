import java.io.*;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import static java.nio.file.StandardWatchEventKinds.ENTRY_CREATE;

public class Main {

    private static final List<String> sourcePaths = new ArrayList<>();
    private static final List<String> subSourcePaths = new ArrayList<>();

    private static String baseDestinationPath = "\\\\Truenas\\ti\\vitor\\backup";

    private static WatchService watchService;
    private static final Map<WatchKey, Path> keyDirectoryMap = new HashMap<>();
    private static final int timeBetweenRuns = 5; // SECONDS

    private static final List<Path> fileThatCannotBeModified = new ArrayList<>();
    private static final LocalDateTime limitTimeForFileAndFolderReplace = LocalDateTime.now().minusDays(30); // 30 days
    private static final int limitTimeForFileAndFolderReplaceInDays = 30; // days

    private static final long MAX_BYTES_PER_SECOND = 20 * 1024 * 1024; // caso coloque 20 irá Limitar a 60~70mbps por causa do buffer ser de 4096


    public static void main(String[] args) throws IOException {
        if(!logArquiveExists()){
            createLogArquive();
        }

        if (!verifyArquivePathControlExists()){
            createArquivePathControl();
        }

        if(!verifyArquiveDestinationExists()){
            createArquiveDestination();
        }

        if(verifyArquiveDestinationExists()){
            baseDestinationPath = readArquiveDestinationPath();
        }

        if(!verifyArquiveSourcesPathsExists()){
            createArquiveSourcesPaths();
        }

        if(verifyArquiveSourcesPathsExists()){
            List<String> paths = readArquiveSourcesPaths();
            sourcePaths.addAll(paths);
            addLog("Info: SourcesPaths add, " + sourcePaths);
        }

        for(int i = 0; i < sourcePaths.size(); i++) {
            Path actualPath = Paths.get(sourcePaths.get(i));

            try {
                // Lista os elementos (subpastas) dentro do diretório
                List<String> subdirectories = Files.list(actualPath)
                        .filter(Files::isDirectory) // Filtra apenas subpastas
                        .map(Path::toString) // Converte Path para String
                        .toList(); // Filtra para pegar apenas subpastas
                subSourcePaths.addAll(subdirectories);
                addLog("Info: add subSourcesPath");
            } catch (IOException e) {
                addLog("Error: ao adicionar os source paths" + e);
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

        if(verifyFirstBackup()){
            try{
                performFirstBackup();
                addLog("Info: First backup performed");
            } catch (Exception e){
                addLog("Error: Primeiro backup falhou" + e);
            }

        }

        // Criar um ScheduledExecutorService para executar a cada 1 minuto
        ScheduledExecutorService executor = Executors.newScheduledThreadPool(1);
        executor.scheduleAtFixedRate(Main::monitorEvents, 0, timeBetweenRuns, TimeUnit.SECONDS);

        // O programa vai rodar indefinidamente.
        try {
            Thread.sleep(Long.MAX_VALUE); // Mantém o programa em execução
        } catch (InterruptedException e) {
            addLog("Error: thread " + e);
            Thread.currentThread().interrupt(); // Lida com a interrupção do thread
        } finally {
            addLog("End: thread finished");
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
                            addLog("Error: monitor events HandlePathCopy " + e);
                        }
                    }
                }
                key.reset();
            }
        } catch (Exception e) {
            addLog("Error: monitor events " + e);
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
                                addLog("Info: recursive backup");
                                copyRecursive(fullSourcePath, destination);
                                recursiveAddArquiveToWatcher(fullSourcePath);
                            } catch (Exception e ){
                                addLog("Error: recursive add " + e);
                            }
                        } else if(checkIfCanUpdateFile(fullSourcePath)){
                            Path newDestination = Path.of(baseDestinationPath + "\\" + fullSourcePath.getParent().getFileName() + "\\" + fullSourcePath.getFileName());
                            Instant timeStamp = Instant.now();
                            addSourcePathControlArquive(fullSourcePath, newDestination, timeStamp);
                            copyFileWithLimit(fullSourcePath, newDestination);
                            addLog("Info: File Backup. Source " + fullSourcePath + " Destination " + newDestination);
                        } else if(!checkIfCanUpdateFile(fullSourcePath)){
                            Path newDestination = Path.of(destination.getParent() + "\\Novo - " + relativePath.replace("\\", ""));
                            Files.copy(fullSourcePath, newDestination, StandardCopyOption.REPLACE_EXISTING);
                            addLog("Info: Archive older than 30 days backup Source: " + fullSourcePath + " new destination " + newDestination);
                        }

                    } catch (Exception e) {
                        addLog("Error: handle copy " + e);
                    }
                }

            }
        }
    }

    // Função para copiar o arquivo com limite de velocidade
    private static void copyFileWithLimit(Path source, Path destination) throws IOException, InterruptedException {

        //pode ser substituido por:
        //Files.copy(sourcePath, destinatioPath, StandardCopyOption.REPLACE_EXISTING);

        try (InputStream in = new BufferedInputStream(Files.newInputStream(source));
             OutputStream out = new BufferedOutputStream(Files.newOutputStream(destination))) {
            byte[] buffer = new byte[4096];
            long startTime = System.currentTimeMillis();
            long bytesTransferred = 0;
            int bytesRead;

            while ((bytesRead = in.read(buffer)) != -1) {
                out.write(buffer, 0, bytesRead);
                bytesTransferred += bytesRead;

                // Se já transferiu mais do que o limite de bytes por segundo
                if (bytesTransferred >= MAX_BYTES_PER_SECOND) {
                    long currentTime = System.currentTimeMillis();
                    long elapsedTime = currentTime - startTime;

                    // Se passou menos de 1 segundo, aguarde o tempo restante
                    if (elapsedTime < 1000) {
                        Thread.sleep(1000 - elapsedTime);
                    }

                    // Reinicie o contador
                    startTime = System.currentTimeMillis();
                    bytesTransferred = 0;
                }
            }
        } catch(Exception e){
            addLog("Error:  copyFileWithLimit " + e);
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
                  try {
                      addLog("Info: Recursive add file: " + file + " to destination " + destination);
                      copyFileWithLimit(file,destinationFile);
                  } catch (InterruptedException e) {
                      addLog("Error:  InternalCopyRecursive1 " + e);
                  }
              } else{
                  String relativePath = String.valueOf(source.relativize(file));
                  Path newDestination = Path.of(destination.getParent() + "\\Novo - " + relativePath.replace("\\", ""));
                  try {
                      addLog("Info: Archive older than 30 days backup Source: " + relativePath + " new destination " + newDestination);
                      copyFileWithLimit(file,newDestination);
                  } catch (InterruptedException e) {
                      addLog("Error:  InternalCopyRecursive2 " + e);
                  }
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
            addLog("Error:  AddSourcePathToControlArquive " + e);
        }

    }

    private static void addLog(String log){

        String arquiveName = "Log.txt";

        LocalDateTime now = LocalDateTime.now();
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm:ss");
        String formattedDateTime = now.format(formatter);

        log = formattedDateTime + " - " + log;

        try (FileWriter writer = new FileWriter(arquiveName, true)) { // 'true' ativa o modo append
            writer.write(log + System.lineSeparator()); // Adiciona mensagem e quebra de linha
        } catch (IOException e) {
            System.out.println("Erro no add log " + e);
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
                    addLog("addFilesThatCannotBeModified1 " + e);
                }
            });
        } catch (IOException e) {
            addLog("addFilesThatCannotBeModified2 " + e);
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
            addLog("checkIfCanUpdateFile " + e);
        }
        return true; //mudar para false
    }

    private static void createLogArquive(){
        try{
            File logFile = new File("Log.txt");
            if(logFile.createNewFile()){
                addLog("Info: created log arquive.");
            }
        } catch (Exception e){
            addLog("Error: log arquive do not be created, " + e);
        }
    }

    private static void createArquivePathControl(){
        try{
            File pathControlFile = new File("PathControl.txt");

            if(pathControlFile.createNewFile()){
                addLog("Info: pathControlFile created");
            } else {
                addLog("Info: pathControlFile exists");
            }
        } catch (Exception e){
            addLog("Error: createPathControlArquive " + e);
        }
    }

    private static void createArquiveDestination(){
        try{
            File destinationFile = new File("BackupDestination.txt");

            if(destinationFile.createNewFile()){
                addLog("Info: file backupDestionation created");
            } else {
                addLog("Info: file backupDestionation exists");
            }
        } catch (Exception e){
            addLog("Error: createArquiveDestination, " + e);
        }
    }

    private static void createArquiveSourcesPaths(){
        try{
            File backupFile = new File("BackupSources.txt");

            if(backupFile.createNewFile()){
                addLog("Info: file backup sources created.");
            } else {
                addLog("Info: file backup sources exists.");
            }
        } catch (Exception e){
            addLog("Error: createArquiveSourcesPaths, " + e);
        }
    }

    private static List<String> readArquiveSourcesPaths(){

        File backupFile = new File("BackupSources.txt");
        List<String> lines = null;

        try {
            // Lê todas as linhas do arquivo e armazena na lista 'lines'
            lines = Files.readAllLines(Path.of(backupFile.getPath()));
            addLog("Read: readArquiveSourcesPaths, sources " + lines);
        } catch (IOException e) {
            addLog("Error: readArquiveSourcesPaths, " + e);
        }

        return lines;
    }

    private static String readArquiveDestinationPath(){
        File destinationFile = new File("BackupDestination.txt");
        String destinationPath = ""; // TODO adicionar um path padrão

        try (BufferedReader reader = new BufferedReader(new FileReader(destinationFile))){
            destinationPath = reader.readLine();
            addLog("Read: readArquiveDestinationPath. destination: " + destinationPath);
        } catch(Exception e){
            addLog("Error: readArquiveDestinationPath, " + e);
        }

        return destinationPath;
    }

    private static boolean logArquiveExists(){
        File pathLogFile = new File("Log.txt");
        if(pathLogFile.exists()){
            return true;
        }
        return false;
    }

    private static boolean verifyArquivePathControlExists(){
        File pathControlFile = new File("PathControl.txt");

        if(pathControlFile.exists()){
            addLog("Verify: PathControlFile exists");
            return true;
        }
        addLog("Verify: PathControlFile not exists");
        return false;
    }

    private static boolean verifyArquiveDestinationExists(){
        File destinationFile = new File("BackupDestination.txt");

        if(destinationFile.exists()){
            addLog("Verify: BackupDestination exists");
            return true;
        }
        addLog("Verify: BackupDestination do not exists");
        return false;
    }

    private static boolean verifyArquiveSourcesPathsExists(){
        File sourcesFile = new File("BackupSources.txt");

        if(sourcesFile.exists()){
            addLog("Verify: BackupSources exists");
            return true;
        }
        addLog("Verify: BackupSources do not exists");
        return false;
    }

    private static boolean verifyFirstBackup() {
        try {
            File firstBackupFile = new File("firstBackup.txt");

            // Verifica se o arquivo já existe
            if (firstBackupFile.exists()) {
                return false; // Indica que o arquivo já existia
            } else {
                // Cria o arquivo se ele não existir
                if (firstBackupFile.createNewFile()) {
                    return true; // Indica que o arquivo foi criado com sucesso
                }
            }
        } catch (Exception e) {
            addLog("Error: Verify/create first backup, " + e);
        }
        return false;
    }

    private static void performFirstBackup() throws Exception{

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
                addLog("Error: performFirstBackup " + e);
            }
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
            addLog("Error: Read - ModifyTimestampFromSourcePathControlArquive, " + e);
        }

        try {
            PrintWriter writer = new PrintWriter(new FileWriter(arquiveName));

            for(String line : lines){
                writer.println(line);
            }

            writer.close();
        } catch (Exception e) {
            addLog("Error: Write - ModifyTimestampFromSourcePathControlArquive, " + e);
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