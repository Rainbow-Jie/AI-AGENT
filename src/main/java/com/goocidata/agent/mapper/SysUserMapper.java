package com.goocidata.agent.mapper;

import com.goocidata.agent.entity.SysUser;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/**
 * 用户 Mapper
 *
 * @author nzj
 */
@Mapper
public interface SysUserMapper {

    @Select("SELECT * FROM sys_user WHERE username = #{username}")
    SysUser findByUsername(@Param("username") String username);

    @Select("SELECT * FROM sys_user WHERE id = #{id}")
    SysUser findById(@Param("id") String id);

    @Select("<script>"
            + "SELECT * FROM sys_user "
            + "<where>"
            + "  <if test='kw != null and kw != \"\"'>"
            + "    (username LIKE '%' || #{kw} || '%' OR nickname LIKE '%' || #{kw} || '%')"
            + "  </if>"
            + "</where>"
            + "ORDER BY created_at DESC"
            + "</script>")
    List<SysUser> list(@Param("kw") String kw);

    @Select("SELECT COUNT(1) FROM sys_user")
    long count();

    @Insert("INSERT INTO sys_user(id, username, password, nickname, avatar, role, status, last_login_at, created_at, updated_at) "
            + "VALUES(#{id}, #{username}, #{password}, #{nickname}, #{avatar}, #{role}, #{status}, #{lastLoginAt}, #{createdAt}, #{updatedAt})")
    int insert(SysUser user);

    @Update("UPDATE sys_user SET nickname = #{nickname}, avatar = #{avatar}, role = #{role}, status = #{status}, "
            + "password = #{password}, updated_at = #{updatedAt} WHERE id = #{id}")
    int update(SysUser user);

    /**
     * 用户自助修改资料：只允许动昵称和头像，角色/状态/密码走各自的管理接口
     */
    @Update("UPDATE sys_user SET nickname = #{nickname}, avatar = #{avatar}, updated_at = #{updatedAt} WHERE id = #{id}")
    int updateProfile(@Param("id") String id,
                      @Param("nickname") String nickname,
                      @Param("avatar") String avatar,
                      @Param("updatedAt") String updatedAt);

    @Update("UPDATE sys_user SET last_login_at = #{lastLoginAt} WHERE id = #{id}")
    int touchLogin(@Param("id") String id, @Param("lastLoginAt") String lastLoginAt);

    @Delete("DELETE FROM sys_user WHERE id = #{id}")
    int delete(@Param("id") String id);
}
