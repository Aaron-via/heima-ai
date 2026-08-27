package org.example.heimaai.controller;

import lombok.RequiredArgsConstructor;
import org.example.heimaai.entity.vo.MessageVO;
import org.example.heimaai.repository.ChatHistoryRepository;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.Message;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RequiredArgsConstructor
@RestController
@RequestMapping("/ai/history")
public class ChatHistoryController {
    private final ChatHistoryRepository chatHistoryRepository;
    private final ChatMemory chatMemory;
    @GetMapping("/{type}")      //意思是从 URL 路径中提取变量
    public List<String> getChatIds(@PathVariable("type") String type){

        return chatHistoryRepository.getChatIds(type);
    }

    @GetMapping("/{type}/{chatId}")
    public List<MessageVO> getChatHistory(@PathVariable("type")String type,@PathVariable("chatId")String chatId){
        List<Message> messages = chatMemory.get(chatId);
        if(messages==null){
            return List.of();
        }
        return messages.stream().map(MessageVO::new).toList();//转化成流式也就是一个一个处理：.map(message -> new MessageVO(message))，然后全部换成list

    }
}
