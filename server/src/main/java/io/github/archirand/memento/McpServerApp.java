package io.github.archirand.memento;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.json.jackson2.JacksonMcpJsonMapper;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.transport.StdioServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.ProtocolVersions;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;

/**
 * The stdio MCP server.
 */
final class McpServerApp
{
    static final String VERSION = "0.1.0";

    private McpServerApp()
    {
    }

    static void run(Vault vault) throws IOException
    {
        CallLog log = new CallLog(vault, Clock.systemUTC(), "server");
        log.prune();
        var transport = new StdioServerTransportProvider(new JacksonMcpJsonMapper(new ObjectMapper()))
        {
            @Override
            public List<String> protocolVersions()
            {
                return List.of(ProtocolVersions.MCP_2025_11_25, ProtocolVersions.MCP_2025_06_18,
                               ProtocolVersions.MCP_2025_03_26, ProtocolVersions.MCP_2024_11_05);
            }
        };
        McpSyncServer server = McpServer.sync(transport)
                                        .serverInfo("memento", VERSION)
                                        .instructions(instructions())
                                        .capabilities(McpSchema.ServerCapabilities.builder().tools(true).build())
                                        .tools(new Tools(vault, Clock.systemUTC(), Path.of("").toAbsolutePath(), log).all())
                                        .build();
        Runtime.getRuntime().addShutdownHook(new Thread(server::closeGracefully));
    }

    static String instructions() throws IOException
    {
        try (InputStream in = McpServerApp.class.getResourceAsStream("/instructions.md"))
        {
            if (in == null)
            {
                throw new IOException("instructions.md is missing from the jar");
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
