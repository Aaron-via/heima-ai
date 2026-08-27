package org.example.heimaai.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.example.heimaai.entity.po.School;
import org.example.heimaai.service.SchoolService;
import org.example.heimaai.mapper.SchoolMapper;
import org.springframework.stereotype.Service;

/**
* @author kk
* @description 针对表【school(校区表)】的数据库操作Service实现
* @createDate 2026-08-21 04:22:51
*/
@Service
public class SchoolServiceImpl extends ServiceImpl<SchoolMapper, School>
    implements SchoolService{

}




