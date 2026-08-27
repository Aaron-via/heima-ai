package org.example.heimaai.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.example.heimaai.entity.po.Course;
import org.example.heimaai.service.CourseService;
import org.example.heimaai.mapper.CourseMapper;
import org.springframework.stereotype.Service;

/**
* @author kk
* @description 针对表【course(学科表)】的数据库操作Service实现
* @createDate 2026-08-21 04:22:51
*/
@Service
public class CourseServiceImpl extends ServiceImpl<CourseMapper, Course>
    implements CourseService{

}




