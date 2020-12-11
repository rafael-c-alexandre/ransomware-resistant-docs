package client;

import PBKDF2.PBKDF2Main;
import SelfSignedCertificate.SelfSignedCertificate;
import com.google.protobuf.ByteString;
import io.grpc.ManagedChannel;
import io.grpc.netty.GrpcSslContexts;
import io.grpc.netty.NettyChannelBuilder;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import org.apache.commons.io.FileUtils;
import proto.*;
import pt.ulisboa.tecnico.sdis.zk.ZKNaming;
import pt.ulisboa.tecnico.sdis.zk.ZKNamingException;
import pt.ulisboa.tecnico.sdis.zk.ZKRecord;

import javax.crypto.*;
import javax.net.ssl.SSLException;
import java.io.*;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.*;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.interfaces.RSAPrivateKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.*;
import java.util.stream.Collectors;


public class Client {
    private static final int INDEX_PATH = 0;
    private static final int INDEX_UID = 1;
    private static final int INDEX_PART_ID = 2;
    private static final int INDEX_NAME = 3;
    private static final int INDEX_USERNAME = 4;

    private static final String SIRS_DIR = System.getProperty("user.dir");
    private static final String FILE_MAPPING_PATH = SIRS_DIR + "/src/assets/data/fm.txt";
    private static final String PULLS_DIR = SIRS_DIR + "/src/assets/clientPulls/";
    private final String zooHost;
    private final String zooPort;
    private final EncryptionLogic e;
    private final ClientFrontend c;
    KeyStore keyStore;
    private String username = null;
    private byte[] salt = null;


    public Client(String zooHost,
                  String zooPort,
                  SslContext sslContext) {

        this.zooHost = zooHost;
        this.zooPort = zooPort;

        String path;

        //client does not need to know key store implementation
        /*
        Console console = System.console();
        String passwd = new String(console.readPassword("Enter private Key keyStore password: "));
         */
        KeyStore ks = null;
        try {
            ks = KeyStore.getInstance("PKCS12");
        } catch (KeyStoreException e) {
            e.printStackTrace();
        }
        try {
            assert ks != null;
            ks.load(new FileInputStream("src/assets/keyStores/clientStore.p12"), "vjZx~R::Vr=s7]bz#".toCharArray());
            this.keyStore = ks;
        } catch (NoSuchAlgorithmException | CertificateException | IOException e) {
            e.printStackTrace();
        }

        System.out.println(zooHost + ":" + zooPort);
        ZKNaming zkNaming = new ZKNaming(zooHost, zooPort);


        path = "/sirs/ransomware/server";
        ZKRecord record = null;
        try {
            record = zkNaming.lookup(path);
            System.out.println("");
        } catch (ZKNamingException e) {
            e.printStackTrace();
        }


        assert record != null;
        /* Only for using provided test certs. */
        ManagedChannel channel = NettyChannelBuilder.forTarget(record.getURI())
                .overrideAuthority("foo.test.google.fr")  /* Only for using provided test certs. */
                .sslContext(sslContext)
                .build();
        ServerGrpc.ServerBlockingStub blockingStub = ServerGrpc.newBlockingStub(channel);
        this.c = new ClientFrontend(blockingStub, channel);
        this.e = new EncryptionLogic();
    }

    private static SslContext buildSslContext(String trustCertCollectionFilePath,
                                              String clientCertChainFilePath,
                                              String clientPrivateKeyFilePath) throws SSLException {
        SslContextBuilder builder = GrpcSslContexts.forClient();
        if (trustCertCollectionFilePath != null) {
            builder.trustManager(new File(trustCertCollectionFilePath));
        }
        if (clientCertChainFilePath != null && clientPrivateKeyFilePath != null) {
            builder.keyManager(new File(clientCertChainFilePath), new File(clientPrivateKeyFilePath));
        }
        return builder.build();
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 5) {
            System.out.println("USAGE: zooHost zooPort trustCertCollectionFilePath " +
                    "clientCertChainFilePath clientPrivateKeyFilePath");
            System.exit(0);
        }
        /* Use default CA. Only for real server certificates. */
        Client client = new Client(args[0], args[1],
                buildSslContext(args[2], args[3], args[4]));

        try {
            Scanner in = new Scanner(System.in);
            boolean running = true;
            while (running) {
                System.out.print("Enter command ( Type 'help' for help menu ): ");
                String cmd = in.nextLine();
                switch (cmd) {
                    case "login" -> client.login();
                    case "register" -> client.register();
                    case "push" -> client.push();
                    case "pull" -> client.pull();
                    case "giveperm" -> client.givePermission();
                    case "rollback" -> client.revertRemoteFile();
                    case "logout" -> client.logout();
                    case "exit" -> running = false;
                    case "help" -> client.displayHelp();
                    default -> System.err.println("Error: Command not recognized");
                }
            }
        } finally {
            client.c.Shutdown();
        }
    }

    public void register() {

        if (this.username != null) {
            System.err.println("Error: User already logged in. Log out first to register a user");
            return;
        }

        Console console = System.console();
        String name = console.readLine("Enter a username: ");
        boolean match = false;
        String passwd = "";

        while (!match) {
            passwd = new String(console.readPassword("Enter a password: "));
            while (passwd.length() < 8 || passwd.length() > 25) {
                System.err.println("Error: Password must be between 8 and 25 characters, try again");
                passwd = new String(console.readPassword("Enter a password: "));
            }
            String confirmation = new String(console.readPassword("Confirm your password: "));
            if (passwd.equals(confirmation))
                match = true;
            else System.err.println("Error: Password don't match. Try again");
        }

        System.out.println("Registering user " + name + " ...");
        // generate RSA Keys
        KeyPair keyPair = e.generateUserKeyPair();
        PublicKey publicKey = keyPair.getPublic();
        // Get the bytes of the public key
        byte[] publicKeyBytes = publicKey.getEncoded();


        //save secret Key to key store
        X509Certificate[] certificateChain = new X509Certificate[1];
        SelfSignedCertificate certificate = new SelfSignedCertificate();
        try {
            certificateChain[0] = certificate.createCertificate();
        } catch (Exception e) {
            e.printStackTrace();
        }
        try {
            this.keyStore.setKeyEntry(name + "privKey", keyPair.getPrivate(), "".toCharArray(), certificateChain);
            this.keyStore.store(new FileOutputStream("src/assets/keyStores/clientStore.p12"), "vjZx~R::Vr=s7]bz#".toCharArray());
        } catch (KeyStoreException | IOException | NoSuchAlgorithmException | CertificateException e) {
            e.printStackTrace();
        }

        //get salt to produce KDF password hashing
        byte[] salt = PBKDF2Main.getNextSalt();
        RegisterReply response = c.Register(name, e.generateSecurePassword(passwd, salt), publicKeyBytes, salt);
        System.out.println(response.getOk());
    }

    public void login() {
        int tries = 0;
        Console console = System.console();

        if (this.username != null) {
            System.err.println("Error: User already logged in. Log out first to log in with another user");
            return;
        }


        String name = console.readLine("Enter your username: ");

        UsernameExistsReply reply = c.UsernameExists(name);
        if (reply.getOkUsername()) {
            while (tries < 3) {
                //get salt used to produce password in order to compute given password salt and compare
                SaltReply replys = c.Salt(name);
                byte[] salt = replys.getSalt().toByteArray();
                String password = new String(console.readPassword("Enter your password: "));

                VerifyPasswordReply response = c.VerifyPassword(name, e.generateSecurePassword(password, salt));
                if (response.getOkPassword()) {

                    this.username = name;
                    this.salt = salt;

                    System.out.println("Successful Authentication. Welcome " + name + "!");
                    break;
                } else {
                    tries++;
                    System.err.println("Error: Wrong password.Try again");
                }
                if (tries == 3) {
                    System.err.println("Error: Exceeded the number of tries. Program is closing...");
                    System.exit(0);
                }
            }
        } else {
            System.err.println("Error: Username does not exist. Try to login with a different username or register");
        }
    }

    public void logout() {
        this.username = null;
        System.out.println("You have logout successfully");
    }

    //check if filename already exists in the file manager file
    public boolean filenameExists(int index1,int index2, String filename) throws FileNotFoundException {
        boolean exists=false;
        try {
            new FileOutputStream(FILE_MAPPING_PATH, true).close();
        } catch (IOException e) {
            e.printStackTrace();
        }
        Scanner sc = new Scanner(new File(FILE_MAPPING_PATH));

        while (sc.hasNextLine()) {
            String[] s = sc.nextLine().split(" ");
            if (this.username.equals(s[index1]) && filename.equals(s[index2])){
                exists=true;

            }
        }
        return exists;
    }

    //get entries from uid map that verify given index 1,2 and 3
    public Map<String, String> getUidMap(int index1, int index2, int index3) throws FileNotFoundException {
        Map<String, String> fileMapping = new TreeMap<>();
        try {
            new FileOutputStream(FILE_MAPPING_PATH, true).close();
        } catch (IOException e) {
            e.printStackTrace();
        }
        Scanner sc = new Scanner(new File(FILE_MAPPING_PATH));

        while (sc.hasNextLine()) {
            String[] s = sc.nextLine().split(" ");
            if (this.username.equals(s[index3])) {
                String path = s[index1];
                String uid = s[index2];
                fileMapping.put(path, uid);
            }
        }
        return fileMapping;
    }

    public void appendTextToFile(String text, String filePath) {
        try {
            BufferedWriter writer;
            writer = new BufferedWriter(
                    new FileWriter(filePath, true));
            writer.write(text);
            writer.close();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public String generateFileUid(String filePath, String partId, String name, String username) throws IOException {
        if (!getUidMap(INDEX_PATH, INDEX_UID, INDEX_USERNAME).containsKey(filePath)) {
            String uid = UUID.randomUUID().toString();
            String textToAppend = filePath + " " + uid + " " + partId + " " + name + " " + username + "\n";

            appendTextToFile(textToAppend, FILE_MAPPING_PATH);

            return uid;
        } else return getUidMap(INDEX_PATH, INDEX_UID, INDEX_USERNAME).get(filePath);
    }

    //choose a random partition in order to save a new file
    public String getRandomPartition() {
        Random random = new Random();
        ZKNaming zkNaming = new ZKNaming(this.zooHost, this.zooPort);
        ArrayList<ZKRecord> recs = null;
        try {
            recs = new ArrayList<>(zkNaming.listRecords("/sirs/ransomware/backups"));
        } catch (ZKNamingException e) {
            e.printStackTrace();
        }
        assert recs != null;

        String[] split = recs.get(random.nextInt(recs.size())).getPath().split("/");
        return split[split.length - 1].split("_")[0];
    }

    public void push() {
        if (username == null) {
            System.err.println("Error: To push files, you need to login first");
            return;
        }
        int tries = 0;
        while (tries < 3) {
            String passwd = new String((System.console()).readPassword("Enter your password: "));
            VerifyPasswordReply repPass = c.VerifyPassword(this.username, e.generateSecurePassword(passwd, this.salt));
            if (repPass.getOkPassword()) {
                try {
                    String filePath = System.console().readLine("File path: ");
                    boolean isNew = !getUidMap(INDEX_PATH, INDEX_UID, INDEX_USERNAME).containsKey(filePath);
                    File f = new File(filePath);
                    if (!f.exists()) {
                        System.err.println("Error: No such file");
                        return;
                    }
                    byte[] file_bytes = Files.readAllBytes(
                            Paths.get(filePath)
                    );

                    String filename = null;
                    SecretKey fileSecretKey = null;
                    String partId;
                    if (isNew) {
                        //avoid having files with duplicate file names
                        boolean dupFileName=true;
                        while (dupFileName) {
                            filename = System.console().readLine("Filename: ");
                            dupFileName=filenameExists(INDEX_USERNAME,INDEX_NAME,filename);
                            if (dupFileName) {
                                System.err.println("Error: Filename already exists");
                            }
                        }
                        fileSecretKey = e.generateAESKey();
                        partId = getRandomPartition();
                    } else {
                        //it is already in the fm (file manager) file
                        filename = getUidMap(INDEX_PATH, INDEX_NAME, INDEX_USERNAME).get(filePath);
                        partId = getUidMap(INDEX_PATH, INDEX_PART_ID, INDEX_USERNAME).get(filePath);
                    }

                    String uid = generateFileUid(filePath, partId, filename, this.username);
                    byte[] digitalSignature = e.createDigitalSignature(file_bytes, e.getPrivateKey(this.username, this.keyStore));
                    byte[] encryptedAES;
                    byte[] file;
                    byte[] iv;

                    if (isNew) {
                        //Generate new IV
                        Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
                        SecureRandom secureRandom = new SecureRandom();
                        iv = new byte[cipher.getBlockSize()];
                        secureRandom.nextBytes(iv);

                        //get user public key
                        GetPublicKeysByUsernamesReply reply = c.GetPublicKeysByUsernames(this.username);
                        //encrypt file symmetric key with user's public key
                        encryptedAES = e.encryptWithRSA(e.bytesToPubKey(reply.getKeys(0).toByteArray()), fileSecretKey.getEncoded());
                        //encrypt file with symmetric key
                        file = e.encryptWithAES(fileSecretKey, file_bytes, iv);
                    } else {
                        GetAESEncryptedReply res = c.GetAESEncrypted(this.username, this.username, uid, "write");
                        if (res.getAESEncrypted().toByteArray().length == 0) {
                            System.err.println("Error: You have read-only permission for this file ");
                            return;
                        }
                        iv = res.getIv().toByteArray();
                        encryptedAES = res.getAESEncrypted().toByteArray();
                        SecretKey fileSecretKey1 = e.bytesToAESKey(e.getAESKeyBytes(encryptedAES, this.username, this.keyStore));
                        file = e.encryptWithAES(fileSecretKey1, file_bytes, iv);
                    }
                    System.out.println("Sending file to server");


                    PushReply res = c.Push(iv, file, encryptedAES, this.username, digitalSignature, filename, uid, partId);
                    if (res.getOk()) {
                        System.out.println("File uploaded successfully");
                        break;
                    } else {
                        System.err.println("There was a problem");
                    }
                } catch (IOException | NoSuchAlgorithmException | InvalidKeyException | SignatureException | NoSuchPaddingException e) {
                    e.printStackTrace();
                }
            } else {
                System.err.println("Error: Wrong password!");
                tries++;
            }
            if (tries == 3) {
                System.err.println("Error: Exceeded the number of tries. Client logged out.");
                logout();
            }
        }
    }

    public void displayHelp() {
        System.out.println("login - logins on file server");
        System.out.println("register - registers on file server server");
        System.out.println("help - displays help message");
        System.out.println("pull - receives files from server");
        System.out.println("push - sends file to server");
        System.out.println("giveperm - give read/write file access permission to user/s");
        System.out.println("rollback - reverts a file to a previous version");
        System.out.println("logout - exits client");
        System.out.println("exit - exits client");
    }

    public void pull() throws IOException, NoSuchAlgorithmException, SignatureException, InvalidKeyException {
        if (username == null) {
            System.err.println("Error: To pull files, you need to login first");
            return;
        }
        int tries = 0;
        while (tries < 3) {
            String passwd = new String((System.console()).readPassword("Enter a password: "));
            VerifyPasswordReply repPass = c.VerifyPassword(this.username, e.generateSecurePassword(passwd, this.salt));
            if (repPass.getOkPassword()) {
                String choice = ((System.console().readLine("Select which files you want to pull, separated by a blank space. 'all' for pulling every file: ")));
                Map<String, String> uidMap = getUidMap(INDEX_UID, INDEX_PATH, INDEX_USERNAME);
                PullReply reply;
                if (choice.equals("all")) {
                    reply = c.PullAll(this.username);
                } else {
                    String[] fileNames = choice.split(" ");
                    reply = c.PullSelected(this.username, fileNames);
                }
                if (!reply.getOk()) {
                    System.err.println("Error: Something wrong with operations in server!");
                } else {
                    for (int i = 0; i < reply.getFilenamesCount(); i++) {
                        System.out.println("Received file " + reply.getFilenames(i));
                        String version_uid = reply.getVersionUids(i);
                        String file_uid = reply.getFileUids(i);
                        String filename = reply.getFilenames(i);
                        String owner = reply.getOwners(i);
                        String partId = reply.getPartIds(i);
                        byte[] file_data = reply.getFiles(i).toByteArray();
                        byte[] digitalSignature = reply.getDigitalSignatures(i).toByteArray();
                        byte[] ownerPublicKey = reply.getPublicKeys(i).toByteArray();

                        //if file exists, overwrite it

                        byte[] decipheredFileData = new byte[0];
                        try {
                            //decrypt file with symmetric key
                            decipheredFileData = e.decryptSecureFile(file_data, reply.getAESEncrypted(i).toByteArray(), reply.getIvs(i).toByteArray(), this.username, this.keyStore);
                        } catch (BadPaddingException | IllegalBlockSizeException ignored) {
                        }

                        PublicKey pk = e.getPublicKey(ownerPublicKey);
                        //verify file signature

                        if (!e.verifyDigitalSignature(decipheredFileData, digitalSignature, pk)) { //dies here wrong IV
                            System.err.println(" Error: Signature verification failed");
                            //if signature does not match, we will check for an healthy copy of that version in the backup servers
                            RetrieveHealthyVersionsReply reply1 = c.RetrieveHealthyVersions(version_uid);

                            byte[] healthyVersion = null;
                            boolean hasHealthy = false;
                            List<byte[]> backups = reply1.getFilesList().stream()
                                    .map(ByteString::toByteArray)
                                    .collect(Collectors.toList());

                            for (byte[] backup : backups) {
                                byte[] encryptedBackup = backup.clone();
                                try {
                                    decipheredFileData = e.decryptSecureFile(backup, reply.getAESEncrypted(i).toByteArray(), reply.getIvs(i).toByteArray(), this.username, this.keyStore);
                                } catch (BadPaddingException | IllegalBlockSizeException ignored) {
                                }

                                if (e.verifyDigitalSignature(decipheredFileData, digitalSignature, pk)) {
                                    healthyVersion = encryptedBackup;
                                    hasHealthy = true;
                                    break;
                                }
                            }
                            //if there is no copy of an healthy version the backups, the file got corrupted
                            if (!hasHealthy) {
                                System.err.println("This file got corrupted!");
                                continue;
                            }
                            HealCorruptedVersionReply reply2 = c.HealCorruptedVersion(version_uid, file_uid, healthyVersion, partId);

                            if (reply2.getOk()) {
                                System.out.println("Version correctly healed in server");
                            } else {
                                System.err.println("Error healing server version!");
                            }
                        } else {
                            System.out.println("Signature correctly verified");
                        }
                        if (uidMap.containsKey(file_uid))
                            FileUtils.writeByteArrayToFile(new File(uidMap.get(file_uid)), decipheredFileData);
                            //else create it
                        else {
                            //prevents duplicate names from overwriting
                            int dupNumber = 1;
                            Map<String, String> map = getUidMap(INDEX_NAME, INDEX_UID, INDEX_USERNAME);

                            //check if client pulls dir exists
                            File directory = new File(PULLS_DIR + "/" + this.username + "/");
                            if (!directory.exists()) {
                                directory.mkdir();
                            }
                            if (!map.containsKey(filename)) {
                                FileUtils.writeByteArrayToFile(new File(PULLS_DIR + "/" + this.username + "/" + filename), decipheredFileData);
                                String text = PULLS_DIR + this.username + "/" + filename + " " + file_uid + " " + partId + " " + filename + " " + this.username + "\n";
                                appendTextToFile(text, FILE_MAPPING_PATH);
                            } else {
                                while (map.containsKey(filename + dupNumber)) {
                                    dupNumber++;
                                }
                                FileUtils.writeByteArrayToFile(new File(PULLS_DIR + "/" + this.username + "/" + filename + dupNumber), decipheredFileData);
                                String text = PULLS_DIR + this.username + "/" + filename + "(" + dupNumber + ")" + " " + file_uid + " " + partId + " " + filename + " " + this.username + "\n";
                                appendTextToFile(text, FILE_MAPPING_PATH);
                            }
                        }
                    }

                }
                break;
            } else {
                System.err.println("Error: Wrong password!");
                tries++;
            }
            if (tries == 3) {
                System.err.println("Error: Exceeded the number of tries. Client logged out.");
                logout();
            }
        }
    }

    public void givePermission() {
        if (username == null) {
            System.err.println("Error: To give permission, you need to login first");
            return;
        }
        Console console = System.console();
        int tries = 0;
        while (tries < 3) {
            String passwd = new String((System.console()).readPassword("Enter a password: "));
            VerifyPasswordReply repPass = c.VerifyPassword(this.username, e.generateSecurePassword(passwd, this.salt));
            if (repPass.getOkPassword()) {
                String others = console.readLine("Enter the username/s to give permission, separated by a blank space: ");
                String s = System.console().readLine("Select what type of permission:\n -> 'read' for read permission\n -> 'write' for read/write permission\nType of permission: ");
                while (!s.matches("write|read")) {
                    System.err.println("Error: Wrong type of permission");
                    s = System.console().readLine("Type of permission: ");
                }
                String filename = console.readLine("Enter the filename: ");
                String uid;
                String[] othersNames = others.split(" ");
                List<String> existingNames = new ArrayList<>();
                for (String name : othersNames) {
                    if (name.equals(this.username))
                        System.err.println("Error: Cannot give permission to user " + this.username + ". Ignoring..");
                    else {
                        UsernameExistsReply reply = c.UsernameExists(name);
                        if (reply.getOkUsername()) {
                            existingNames.add(name);
                        } else
                            System.err.println("Error: username " + name + " does not exist in the database. Ignoring..");
                    }
                }

                if (existingNames.size() == 0) return;

                try {
                    uid = getUidMap(INDEX_NAME, INDEX_UID, INDEX_USERNAME).get(filename);

                    if (uid == null) {
                        System.err.println("Error: File " + filename + " does not exist in the system");
                        return;
                    }


                } catch (FileNotFoundException e) {
                    System.err.println("Error: File not found exception");
                    return;
                }

                // Gets the owners encrypted key
                GetAESEncryptedReply reply = c.GetAESEncrypted(this.username, existingNames.toArray(new String[0]), uid, s);
                byte[] aesEncrypted = reply.getAESEncrypted().toByteArray();

                //Get users' whose permission is being assigned public keys
                List<byte[]> othersPubKeysBytes = reply
                        .getOthersPublicKeysList()
                        .stream()
                        .map(ByteString::toByteArray)
                        .collect(Collectors.toList());


                byte[] aesKeyBytes;
                if (reply.getIsOwner()) {
                    //decrypt with private key in order to obtain symmetric key
                    aesKeyBytes = e.getAESKeyBytes(aesEncrypted, this.username, this.keyStore);

                    //encrypt AES with "others" public keys to send to the server
                    List<byte[]> othersAesEncrypted = e.getOthersAESEncrypted(othersPubKeysBytes, aesKeyBytes);

                    //read/write permissions
                    GivePermissionReply res = c.GivePermission(othersNames, uid, s, othersAesEncrypted);
                    if (res.getOkOthers()) {
                        if (res.getOkUid()) {
                            for (String name : othersNames) {
                                System.out.println(s + " permission granted for filename " + filename + " for user " + name);
                            }
                            break;
                        }
                    } else System.err.println("Error: Username do not exist");
                    break;
                } else System.err.println("Error: You are not the owner of this file, you cannot give permission");
                break;
            } else {
                System.err.println("Error: Wrong password!");
                tries++;
            }
            if (tries == 3) {
                System.err.println("Error: Exceeded the number of tries. Client logged out.");
                logout();
            }
        }
    }

    public void revertRemoteFile() throws FileNotFoundException {
        if (this.username != null) {
            int tries = 0;
            Console console = System.console();
            while (tries < 3) {
                String passwd = new String(console.readPassword("Enter your password: "));
                VerifyPasswordReply repPass = c.VerifyPassword(this.username, e.generateSecurePassword(passwd, this.salt));
                if (repPass.getOkPassword()) {
                    Map<String, String> map = getUidMap(INDEX_NAME, INDEX_UID, INDEX_USERNAME);
                    Map<String, String> map1 = getUidMap(INDEX_NAME, INDEX_PART_ID, INDEX_USERNAME);
                    String filename = console.readLine("Enter filename to roll back: ");
                    String fileUid = map.get(filename);
                    String partId= map1.get(filename);
                    GetAESEncryptedReply res = c.GetAESEncrypted(this.username, this.username, fileUid, "write");
                    if (res.getAESEncrypted().toByteArray().length == 0) {
                        System.err.println("Error: You have read-only permission for this file");
                        return;
                    }
                    //get list of all available versions for that file
                    ListFileVersionsReply reply = c.ListFileVersions(fileUid);
                    int version = reply.getDatesCount();

                    for (String date : reply.getDatesList()) {
                        System.out.println("Version " + version + " modified on date " + date);
                        version--;
                    }
                    String number = console.readLine("Enter version number to revert into: ");
                    RevertMostRecentVersionReply reply1 = c.RevertMostRecentVersion(reply.getFileIds(reply.getDatesCount() - Integer.parseInt(number)),
                            reply.getVersionsUids(reply.getDatesCount() - Integer.parseInt(number)),partId);

                    if (reply1.getOk()) {
                        System.out.println("Version reverted successfully!");
                    } else {
                        System.err.println("Error: Failed to revert version!");
                    }
                    break;
                } else {
                    System.err.println("Error: Wrong password!");
                    tries++;
                }
                if (tries == 3) {
                    System.err.println("Error: Exceeded the number of tries. Client logged out.");
                    logout();
                }
            }
        } else
        System.err.println("Error: Login First");
    }


}
