package org.example.heimaai.repository;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;
import org.example.heimaai.repository.FileRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;

import java.io.*;
import java.nio.file.Files;
import java.time.LocalDateTime;
import java.util.Objects;
import java.util.Properties;

@Repository
@RequiredArgsConstructor
public class LocalPdfFileRepository implements FileRepository {

    private static final Logger log = LoggerFactory.getLogger(LocalPdfFileRepository.class);

    /**
     * 注意：这里注入的是 VectorStore 接口
     * Spring AI 自动配置会根据 yaml 把 RedisVectorStore 的实现塞进来
     * 不要再强转 SimpleVectorStore
     */
    private final VectorStore vectorStore;

    // 会话id 与 文件名的对应关系，方便查询会话历史时重新加载文件
    private final Properties chatFiles = new Properties();

    @Override
    public boolean save(String chatId, Resource resource) {
        String filename = resource.getFilename();
        File target = new File(Objects.requireNonNull(filename));
        if (!target.exists()) {
            try {
                Files.copy(resource.getInputStream(), target.toPath());
            } catch (IOException e) {
                log.error("Failed to save PDF resource.", e);
                return false;
            }
        }
        // 保存映射关系（内存中，后面可选持久化到 properties 文件）
        chatFiles.put(chatId, filename);
        return true;
    }

    @Override
    public Resource getFile(String chatId) {
        return new FileSystemResource(chatFiles.getProperty(chatId));
    }

    @PostConstruct
    private void init() {
        // 1. 恢复会话-文件映射关系（这部分还是用本地 properties 文件，可选）
        FileSystemResource pdfResource = new FileSystemResource("chat-pdf.properties");
        if (pdfResource.exists()) {
            try {
                chatFiles.load(new BufferedReader(new InputStreamReader(pdfResource.getInputStream())));
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }

        // 2. ❌ 删掉 SimpleVectorStore 的 load(json) 逻辑
        // RedisVectorStore 启动后索引和向量数据都在 Redis 里，自动恢复，不需要 load
        log.info("Redis VectorStore ready, index={}, prefix={}",
                "custom-index", "custom-prefix");
    }

    @PreDestroy
    private void persist() {
        // 1. 只持久化 chatFiles 映射关系到本地文件（和 Redis 无关）
        try {
            chatFiles.store(new FileWriter("chat-pdf.properties"),
                    "saved at " + LocalDateTime.now());
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

        // 2. ❌ 删掉 simpleVectorStore.save("chat-pdf.json")
        // Redis 自己持久化（AOF/RDB），应用不需要做向量落盘
        log.info("Application shutting down, vector data already persisted in Redis Stack.");
    }
}