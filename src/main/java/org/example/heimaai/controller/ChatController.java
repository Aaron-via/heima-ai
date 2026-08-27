package org.example.heimaai.controller;

import lombok.RequiredArgsConstructor;
import org.example.heimaai.repository.ChatHistoryRepository;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.content.Media;
import org.springframework.util.MimeType;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import reactor.core.publisher.Flux;

import java.io.IOException;
import java.util.Base64;
import java.util.List;
import java.util.Objects;

import static org.springframework.ai.vectorstore.redis.RedisVectorStore.MetadataField.text;

@RequiredArgsConstructor
@RestController
@RequestMapping("/ai")
public class ChatController {

    private final ChatClient chatClient;

    private final ChatHistoryRepository chatHistoryRepository;

    @RequestMapping(value="/chat",produces="text/html;charset=utf-8")
    public Flux<String> chat(@RequestParam("prompt") String prompt,
                             @RequestParam("chatId")String chatId,
                             @RequestParam(value = "files",required = false) List<MultipartFile> files){

        //1.保存会话id
        chatHistoryRepository.save("chat",chatId);

        //2.请求模型
        if(files==null||files.isEmpty()){
            //没有附件，纯文本聊天
            return textChat(prompt,chatId);
        }
        else{
            //有附件，多模态聊天
            return multiModalChat(prompt,chatId,files);
        }
    }

    private Flux<String> textChat(String prompt, String chatId) {
        return chatClient.prompt()
                .user(prompt)
                .advisors(a -> a.param("chat_memory_conversation_id", chatId))
                .stream()
                .content();
    }

    private Flux<String> multiModalChat(String prompt, String chatId, List<MultipartFile> files) {
        //1.解析多媒体
        List<Media> medias=files.stream().map(file->new Media(MimeType.valueOf(Objects.requireNonNull(file.getContentType())),file.getResource()))
                .toList();
        //2.请求模型
        return chatClient.prompt()
                .user(u -> {
                    u.text(prompt);
                    // 逐个添加 media
                    for (Media media : medias) {
                        u.media(media);
                    }
                })
                .advisors(a -> a.param("chat_memory_conversation_id", chatId))
                .stream()
                .content();
    }
    /*
    private Flux<String> multiModalChat(String prompt, String chatId, List<MultipartFile> files) {
        //1.解析多媒体 - 改为手动处理音频的 base64 + data URI
        List<Media> medias = files.stream().map(file -> {
            try {
                // 读取文件字节
                byte[] fileBytes = file.getBytes();

                // Base64 编码
                String base64Data = Base64.getEncoder().encodeToString(fileBytes);

                // 关键：拼上 data:;base64, 前缀
                String dataUri = "data:;base64," + base64Data;

                // 获取原始 MIME 类型
                String contentType = file.getContentType();
                if (contentType == null) {
                    contentType = "application/octet-stream";
                }

                // 构造 Media，注意这里用的是 URI.create(dataUri)
                return new Media(
                        MimeType.valueOf(contentType),
                        java.net.URI.create(dataUri)
                );

            } catch (IOException e) {
                throw new RuntimeException("Failed to read uploaded file", e);
            }
        }).toList();

        //2.请求模型（这部分不用改）
        return chatClient.prompt()
                .user(u -> {
                    u.text(prompt);
                    for (Media media : medias) {
                        u.media(media);
                    }
                })
                .advisors(a -> a.param("chat_memory_conversation_id", chatId))
                .stream()
                .content();
    }

     */
}
