package com.integration.intake;

import com.jcraft.jsch.ChannelSftp;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.integration.sftp.session.DefaultSftpSessionFactory;
import org.springframework.integration.sftp.session.SftpRemoteFileTemplate;

/**
 * ═══════════════════════════════════════════════════════════════════
 * SftpConfig — Shared SFTP Session Factory
 * ═══════════════════════════════════════════════════════════════════
 *
 * Builds ONE shared DefaultSftpSessionFactory for the single SFTP server.
 * All use case pollers share this factory — one connection pool,
 * one set of credentials.
 *
 * Connection details from Spring Cloud Config Server (application.properties):
 *   sftp.host, sftp.port
 *
 * Credentials from Hashicorp Vault (secret/integration/sftp):
 *   sftp.username, sftp.password
 *
 * SftpRemoteFileTemplate — Spring Integration utility for SFTP operations:
 *   list(), get(), rename(), remove()
 *   Used by UseCasePoller for all remote file operations.
 *   No manual JSch session/channel management needed.
 */
@Slf4j
@Configuration
@RequiredArgsConstructor
public class SftpConfig {

    /*
     * Connection details from Spring Cloud Config Server.
     */
    @Value("${sftp.host}")
    private String host;

    @Value("${sftp.port:22}")
    private int port;

    /*
     * Credentials injected by Vault (secret/integration/sftp).
     * Never logged.
     */
    @Value("${sftp.username}")
    private String username;

    @Value("${sftp.password}")
    private String password;

    /**
     * Shared SFTP session factory.
     * Spring Integration manages connection pooling and reconnection.
     * allowUnknownKeys=true — acceptable for internal SFTP servers.
     * Replace with known_hosts configuration for stricter environments.
     */
    @Bean
    public DefaultSftpSessionFactory sftpSessionFactory() {
        DefaultSftpSessionFactory factory = new DefaultSftpSessionFactory(true);
        factory.setHost(host);
        factory.setPort(port);
        factory.setUser(username);
        factory.setPassword(password);
        factory.setAllowUnknownKeys(true);

        log.info("SFTP session factory created: host={}:{}", host, port);
        return factory;
    }

    /**
     * SftpRemoteFileTemplate — high-level SFTP operations.
     * Wraps the session factory with template pattern:
     *   execute() — run operations with automatic session open/close
     *   list()    — list remote files
     *   rename()  — move file to processed/ on remote server
     *   remove()  — delete remote file
     *
     * All use case pollers share this single template instance.
     */
    @Bean
    public SftpRemoteFileTemplate sftpRemoteFileTemplate(
            DefaultSftpSessionFactory factory) {
        SftpRemoteFileTemplate template =
            new SftpRemoteFileTemplate(factory);
        template.setAutoCreateDirectory(true);
        return template;
    }
}
