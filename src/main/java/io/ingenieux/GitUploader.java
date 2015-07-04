package io.ingenieux;

import com.amazonaws.services.lambda.runtime.ClientContext;
import com.amazonaws.services.lambda.runtime.CognitoIdentity;
import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.LambdaLogger;
import com.amazonaws.services.s3.AmazonS3Client;
import com.amazonaws.services.s3.model.BucketTaggingConfiguration;
import com.amazonaws.services.s3.model.GetObjectRequest;
import com.amazonaws.services.s3.model.ObjectMetadata;
import com.amazonaws.services.s3.model.TagSet;
import com.amazonaws.util.IOUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jcraft.jsch.JSch;
import com.jcraft.jsch.JSchException;
import com.jcraft.jsch.Session;
import org.apache.commons.io.output.NullOutputStream;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.PushCommand;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.TextProgressMonitor;
import org.eclipse.jgit.transport.JschConfigSessionFactory;
import org.eclipse.jgit.transport.OpenSshConfig;
import org.eclipse.jgit.transport.RefSpec;
import org.eclipse.jgit.transport.SshSessionFactory;
import org.eclipse.jgit.util.FS;
import org.rauschig.jarchivelib.Archiver;
import org.rauschig.jarchivelib.ArchiverFactory;

import java.io.*;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

public class GitUploader {
    public static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final Context context;

    private final AmazonS3Client s3;

    private final InputStream input;

    private final OutputStream output;

    public GitUploader(InputStream input, OutputStream output, Context context) {
        this.input = input;
        this.output = output;
        this.context = context;
        this.s3 = new AmazonS3Client();
    }

    public void execute() throws IOException {
        try {
            ObjectNode objectNode = (ObjectNode) OBJECT_MAPPER.readTree(input);

            executeInternal(objectNode);
        } catch (Exception exc) {
            log("Exception: %s", exc.toString());

            if (exc instanceof IOException)
                throw (IOException) exc;

            throw new RuntimeException(exc);
        }
    }

    public void log(String message, Object... args) {
        context.getLogger().log(String.format(message, args) + "\n");
    }

    public void executeInternal(ObjectNode objectNode) throws Exception {
        log("input: %s", OBJECT_MAPPER.writeValueAsString(objectNode));

        boolean dryRun = objectNode.path("dryRun").asBoolean(false);

        Iterator<JsonNode> itRecord = objectNode.path("Records").elements();

        while (itRecord.hasNext()) {
            JsonNode e = itRecord.next();

            String bucket = e.path("s3").path("bucket").path("name").textValue();
            String key = e.path("s3").path("object").path("key").textValue();
            String version = e.path("s3").path("object").path("version").textValue();

            log("dryRun: %s; bucket: %s; key: %s; version: %s", dryRun, bucket, key, version);

            handleNotification(dryRun, bucket, key, version);
        }
    }

    private void handleNotification(boolean dryRun, String bucket, String key, String version) throws IOException, GitAPIException {
        BucketTaggingConfiguration bucketTaggingConfiguration = s3.getBucketTaggingConfiguration(bucket);

        Map<String, String> config = new HashMap<>();

        for (TagSet x : bucketTaggingConfiguration.getAllTagSets())
            for (Map.Entry<String, String> k : x.getAllTags().entrySet())
                config.put(k.getKey().toLowerCase(), k.getValue());

        ObjectMetadata metadata = s3.getObjectMetadata(bucket, key);

        for (Map.Entry<String, String> e : metadata.getUserMetadata().entrySet()) {
            config.put(e.getKey(), e.getValue());
        }

        for (Map.Entry<String, String> e : config.entrySet()) {
            log("config[%s]: %s", e.getKey(), e.getValue());
        }

        String gitRepository = config.get("git_repository");

        {
            String gitKey = "id_rsa";

            if (config.containsKey("git_key")) {
                gitKey = config.get("git_key");
            }

            File tempKeyFile = File.createTempFile("tmp", ".key");

            s3.getObject(new GetObjectRequest(bucket, gitKey), tempKeyFile);

            setKey(tempKeyFile);
        }

        String basename = getBasename(key);
        String radical = getFileRadical(key);

        String branch = basename;

        if (config.containsKey("git_branch")) {
            branch = config.get("git_branch");
        }

        // TODO: Validate Stuff

        File tempFile = new File("/tmp/archive/" + basename);

        log("tempFile: %s", tempFile.getAbsolutePath());

        s3.getObject(new GetObjectRequest(bucket, key, version), tempFile);

        Archiver archiver = ArchiverFactory.createArchiver(tempFile);

        File outputPath = new File("/tmp/" + radical);

        outputPath.mkdir();

        archiver.extract(tempFile, outputPath);

        log("outputPath: %s", outputPath.getAbsolutePath());

        Git git = Git.init().setDirectory(outputPath).call();

        git.add().setUpdate(true).addFilepattern(".").call();

        git.commit().setAll(true).setMessage("Made from git-motel").call();

        String commitId = ObjectId.toString(git.getRepository().getRef("master").getObjectId());

        if ("commitId".equals(branch)) {
            branch = commitId;
        }

        Ref masterRef = git.getRepository().getRef("master");

        PushCommand push = git.push();

        push.setProgressMonitor(new TextProgressMonitor());

        push.add(masterRef).setRemote(gitRepository);

        RefSpec refSpec = new RefSpec("HEAD:refs/heads/" + branch);
        push.setRefSpecs(refSpec);

        push.setForce(true);

        log("Will push to %s", refSpec.getDestination());

        if (dryRun) {
            return;
        }

        push.call();
    }

    private String getBasename(String key) {
        if (-1 != key.lastIndexOf('/'))
            key = key.substring(1 + key.lastIndexOf('/'));

        return key;
    }

    private String getFileSuffix(String key) {
        if (-1 != key.indexOf('.')) {
            return key.substring(1 + key.indexOf('.'));
        } else {
            return key;
        }
    }

    private String getFileRadical(String key) {
        if (-1 != key.lastIndexOf('/'))
            key = key.substring(1 + key.lastIndexOf('/'));

        if (-1 != key.indexOf('.')) {
            return key.substring(0, key.indexOf('.'));
        } else {
            return key;
        }
    }

    public static void gitUploadHandler(InputStream input, OutputStream output, Context context) throws IOException {
        //setKey(tempKey);

        new GitUploader(input, output, context).execute();
    }

    private void setKey(final File tempKey) {
        SshSessionFactory.setInstance(new JschConfigSessionFactory() {
            @Override
            protected void configure(OpenSshConfig.Host hc, Session session) {
                session.setConfig("StrictHostKeyChecking", "no");
            }

            @Override
            protected JSch getJSch(OpenSshConfig.Host hc, FS fs) throws JSchException {
                JSch result = super.getJSch(hc, fs);

                result.removeAllIdentity();
                result.addIdentity(tempKey.getAbsolutePath());

                return result;
            }
        });
    }

    public static void main(String[] args) throws Exception {
        FileInputStream fis = new FileInputStream("testpayload.json");

        gitUploadHandler(fis, new NullOutputStream(), new DummyContext());

    }

    private static class DummyContext implements Context {
        @Override
        public String getAwsRequestId() {
            return null;
        }

        @Override
        public String getLogGroupName() {
            return null;
        }

        @Override
        public String getLogStreamName() {
            return null;
        }

        @Override
        public String getFunctionName() {
            return null;
        }

        @Override
        public CognitoIdentity getIdentity() {
            return null;
        }

        @Override
        public ClientContext getClientContext() {
            return null;
        }

        @Override
        public int getRemainingTimeInMillis() {
            return 0;
        }

        @Override
        public int getMemoryLimitInMB() {
            return 0;
        }

        @Override
        public LambdaLogger getLogger() {
            return new LambdaLogger() {
                @Override
                public void log(String message) {
                    while (message.endsWith("\n"))
                        message = message.substring(0, -1 + message.length());

                    System.out.println(message);
                }
            };
        }
    }
}
