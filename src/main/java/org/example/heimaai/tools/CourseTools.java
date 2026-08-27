package org.example.heimaai.tools;

import com.baomidou.mybatisplus.extension.conditions.query.QueryChainWrapper;
import lombok.RequiredArgsConstructor;
import org.example.heimaai.entity.po.Course;
import org.example.heimaai.entity.po.CourseReservation;
import org.example.heimaai.entity.po.School;
import org.example.heimaai.entity.query.CourseQuery;
import org.example.heimaai.service.CourseReservationService;
import org.example.heimaai.service.CourseService;
import org.example.heimaai.service.SchoolService;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.List;

@RequiredArgsConstructor
@Component
public class CourseTools {
    private final CourseService courseService;
    private final SchoolService schoolService;
    private final CourseReservationService reservationService;


    @Tool(description = "根据条件查询课程")
    public List<Course> queryCourse(@ToolParam(description = "查询的条件") CourseQuery query){
        if(query==null){
            return courseService.list();
        }
        QueryChainWrapper<Course> wrapper = courseService.query()
                .eq(query.getType() != null, "type", query.getType())
                .eq(query.getEdu() != null, "edu", query.getEdu());
        if(query.getSorts()!=null && !query.getSorts().isEmpty()){
            for(CourseQuery.Sort sort :query.getSorts()){
                wrapper.orderBy(true,sort.getAsc(),sort.getField());
            }
        }

        return wrapper.list();

    }

    @Tool(description="查询所有校区")
    public List<School> querySchool(){
        return schoolService.list();
    }

    @Tool(description = "生成预约单，返回预约单号")
    public Integer createCourseReservation(
            @ToolParam(description = "预约课程") String course,
            @ToolParam(description = "预约校区") String school,
            @ToolParam(description = "预约学生") String studentName,
            @ToolParam(description = "联系方式") String contactInfo,
            @ToolParam(description = "备注",required = false) String remark){
        CourseReservation reservation=new CourseReservation();
        reservation.setCourse(course);
        reservation.setSchool(school);
        reservation.setStudentName(studentName);
        reservation.setContactInfo(contactInfo);
        reservation.setRemark(remark);
        reservationService.save(reservation);
        return reservation.getId();
    }

}
