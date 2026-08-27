package org.example.heimaai.controller;

import lombok.RequiredArgsConstructor;
import org.example.heimaai.repository.ChatHistoryRepository;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

@RequiredArgsConstructor
@RestController
@RequestMapping("/ai")
public class ServiceController {

    private final ChatClient serviceChatClient;

    private final ChatHistoryRepository chatHistoryRepository;

    @RequestMapping(value="/service",produces="text/html;charset=utf-8")
    public Flux<String> chat(String prompt,String chatId){

        //1.保存会话id
        chatHistoryRepository.save("service",chatId);

        //2.请求模型
        return serviceChatClient.prompt()
                .user(prompt)
                .advisors(a -> a.param("chat_memory_conversation_id", chatId))
                .stream()
                .content();
    }

}
