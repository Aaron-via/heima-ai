package org.example.heimaai.config;

import com.openai.client.OpenAIClient;
import com.openai.client.OpenAIClientAsync;
import com.openai.client.okhttp.OpenAIOkHttpClient;
import io.micrometer.observation.ObservationRegistry;
import org.example.heimaai.SystemConstants;
import org.example.heimaai.model.AlibabaOpenAiChatModel;
import org.example.heimaai.tools.CourseTools;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.client.advisor.SimpleLoggerAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.InMemoryChatMemoryRepository;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.OpenAiEmbeddingModel;
import org.springframework.ai.rag.advisor.RetrievalAugmentationAdvisor;
import org.springframework.ai.rag.retrieval.search.VectorStoreDocumentRetriever;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.redis.RedisVectorStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import redis.clients.jedis.RedisClient;

@Configuration
public class CommonConfiguration {

    @Value("${spring.ai.openai.base-url}")
    private String baseUrl;

    @Value("${spring.ai.openai.api-key}")
    private String apiKey;

    @Value("${spring.ai.openai.chat.options.model:qwen-omni-turbo}")
    private String model;

    @Bean
    public AlibabaOpenAiChatModel alibabaOpenAiChatModel(
            ObservationRegistry observationRegistry,
            ToolCallingManager toolCallingManager) {

        // 手动创建客户端
        OpenAIClient client = OpenAIOkHttpClient.builder()
                .baseUrl(baseUrl)
                .apiKey(apiKey)
                .build();

        OpenAIClientAsync asyncClient = client.async();

        OpenAiChatOptions options = OpenAiChatOptions.builder()
                .model(model)
                .build();

        return new AlibabaOpenAiChatModel(client, asyncClient, options, observationRegistry, toolCallingManager);
    }

    @Bean
    public ChatMemory chatMemory() {
        return MessageWindowChatMemory.builder()
                .chatMemoryRepository(new InMemoryChatMemoryRepository())
                .maxMessages(20)
                .build();
    }

    @Bean
    public RedisClient redisClient() {
        return RedisClient.create("redis://localhost:6379");
    }

    @Bean
    public VectorStore redisVectorStore(RedisClient redisClient, OpenAiEmbeddingModel openAiEmbeddingModel) {
        return RedisVectorStore.builder(redisClient, openAiEmbeddingModel)
                .initializeSchema(true)
                .indexName("custom-index")
                .prefix("custom-prefix")
                .metadataFields(
                        RedisVectorStore.MetadataField.tag("file_name"),
                        RedisVectorStore.MetadataField.numeric("page_number")
                )
                .build();
    }

    @Bean
    public ChatClient chatClient(AlibabaOpenAiChatModel model, ChatMemory chatMemory){
        return ChatClient
                .builder(model)
                .defaultOptions(OpenAiChatOptions.builder().model("qwen-omni-turbo"))
                .defaultSystem("你是一个热心善良的智能助手，名字叫奶龙")
                .defaultAdvisors(new SimpleLoggerAdvisor(), MessageChatMemoryAdvisor.builder(chatMemory).build())
                .build();
    }

    @Bean
    public ChatClient gameChatClient(OpenAiChatModel model, ChatMemory chatMemory){
        return ChatClient
                .builder(model)
                .defaultSystem(SystemConstants.GAME_SYSTEM_PROMPT)
                .defaultAdvisors(new SimpleLoggerAdvisor(), MessageChatMemoryAdvisor.builder(chatMemory).build())
                .build();
    }

    @Bean
    public ChatClient serviceChatClient(OpenAiChatModel model, ChatMemory chatMemory, CourseTools courseTools){
        return ChatClient
                .builder(model)
                .defaultSystem(SystemConstants.SERVICE_SYSTEM_PROMPT)
                .defaultAdvisors(new SimpleLoggerAdvisor(), MessageChatMemoryAdvisor.builder(chatMemory).build())
                .defaultTools(courseTools)
                .build();
    }

    @Bean
    public ChatClient pdfChatClient(OpenAiChatModel model,
                                    ChatMemory chatMemory,
                                    VectorStore vectorStore) {
        var retriever = VectorStoreDocumentRetriever.builder()
                .vectorStore(vectorStore)
                .similarityThreshold(0.6)
                .topK(2)
                .build();

        return ChatClient.builder(model)
                .defaultSystem("你是一个PDF文档助手，仅根据上下文回答,遇到上下文没有的问题，不要随意编造")
                .defaultAdvisors(
                        MessageChatMemoryAdvisor.builder(chatMemory).build(),
                        new SimpleLoggerAdvisor(),
                        RetrievalAugmentationAdvisor.builder()
                                .documentRetriever(retriever)
                                .build()
                )
                .build();
    }
}