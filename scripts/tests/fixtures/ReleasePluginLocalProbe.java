import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import org.apache.commons.io.IOUtils;
import org.apache.maven.scm.provider.git.jgit.JGitScmProvider;
import org.apache.sshd.client.SshClient;
import org.apache.sshd.sftp.client.SftpClientFactory;
import org.codehaus.plexus.util.StringUtils;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.internal.transport.sshd.JGitSshClient;
import org.eclipse.jgit.transport.sshd.SshdSessionFactory;
import org.eclipse.jgit.transport.sshd.SshdSessionFactoryBuilder;

/** Local construction/linkage checks; never connects, commits, tags or releases. */
public class ReleasePluginLocalProbe {
    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
        System.out.println("PASS " + message);
    }

    private static void origin(Class<?> type, String filename, Set<Path> approved) throws Exception {
        Path actual = Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI()).toRealPath();
        check(actual.getFileName().toString().equals(filename) && approved.contains(actual),
                type.getName() + " uses exact resolved artifact " + filename);
    }

    public static void main(String[] args) throws Exception {
        Path directory = Path.of(args[0]);
        Set<Path> approved = new HashSet<>();
        for (int index = 1; index < args.length; index++) approved.add(Path.of(args[index]).toRealPath());
        origin(Git.class, "org.eclipse.jgit-5.13.5.202508271544-r.jar", approved);
        origin(SshdSessionFactory.class, "org.eclipse.jgit.ssh.apache-5.13.5.202508271544-r.jar", approved);
        origin(SshClient.class, "sshd-osgi-2.16.0.jar", approved);
        origin(SftpClientFactory.class, "sshd-sftp-2.16.0.jar", approved);
        origin(IOUtils.class, "commons-io-2.20.0.jar", approved);
        origin(StringUtils.class, "plexus-utils-4.0.3.jar", approved);
        origin(JGitScmProvider.class, "maven-scm-provider-jgit-2.2.1.jar", approved);

        Path repository = directory.resolve("repository");
        try (Git git = Git.init().setDirectory(repository.toFile()).call()) {
            check(git.getRepository().getConfig().getSubsections("remote").isEmpty(),
                    "disposable repository has no remotes");
            check(git.status().call().isClean(), "local empty repository status");
            Files.writeString(repository.resolve("marker.txt"), "bounded local compatibility\n");
            check(git.status().call().getUntracked().equals(Set.of("marker.txt")),
                    "JGit reads local untracked status without commit or network");
        }
        Path home = Files.createDirectories(directory.resolve("home"));
        Path ssh = Files.createDirectories(home.resolve(".ssh"));
        try (SshdSessionFactory factory = new SshdSessionFactoryBuilder()
                .setHomeDirectory(home.toFile()).setSshDirectory(ssh.toFile()).build(null)) {
            check(factory.getType().equals("mina-sshd"), "JGit SSH factory constructs and closes locally");
            check(factory.getHomeDirectory().equals(home.toFile()), "SSH factory uses isolated local home");
        }
        try (JGitSshClient client = new JGitSshClient()) {
            check(!client.isStarted(), "JGit SSH client links to patched SSHD without starting");
        }
        try (SshClient client = SshClient.setUpDefaultClient()) {
            check(!client.isStarted(), "patched SSHD default client constructs without connecting");
        }
        check(SftpClientFactory.instance() != null, "patched SFTP factory links without a session");
        check(new JGitScmProvider(null) != null, "SCM 2.2.1 JGit provider constructs with the patched realm");
        check(IOUtils.toString(new ByteArrayInputStream("local-io".getBytes(StandardCharsets.UTF_8)),
                StandardCharsets.UTF_8).equals("local-io"), "Commons IO bounded memory stream");
        check(StringUtils.isNotEmpty("local-plexus"), "Plexus utility linkage");
        System.out.println("PROBE_COMPLETE: local init/status and unstarted SSH/SFTP factories only; "
                + "no remote transport, release workflow, exploitability or scanner claim");
    }
}
