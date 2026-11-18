package com.smartflow.server.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.smartflow.pojo.entity.Role;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/** 角色 Mapper（含用户角色/权限查询）。 */
@Mapper
public interface RoleMapper extends BaseMapper<Role> {

    @Select("""
            SELECT r.role_code FROM sf_role r
            JOIN sf_user_role ur ON ur.role_id = r.id
            WHERE ur.user_id = #{userId}
            """)
    List<String> selectRoleCodesByUserId(@Param("userId") Long userId);

    @Select("""
            SELECT DISTINCT p.perm_code FROM sf_permission p
            JOIN sf_role_permission rp ON rp.perm_id = p.id
            JOIN sf_user_role ur ON ur.role_id = rp.role_id
            WHERE ur.user_id = #{userId}
            """)
    List<String> selectPermCodesByUserId(@Param("userId") Long userId);

    /** 某角色下的用户 id 列表（审批指派）。 */
    @Select("""
            SELECT ur.user_id FROM sf_user_role ur
            JOIN sf_role r ON r.id = ur.role_id
            WHERE r.role_code = #{roleCode}
            """)
    List<Long> selectUserIdsByRoleCode(@Param("roleCode") String roleCode);

    @org.apache.ibatis.annotations.Update("DELETE FROM sf_user_role WHERE user_id = #{userId}")
    int clearUserRoles(@Param("userId") Long userId);

    @org.apache.ibatis.annotations.Insert("INSERT INTO sf_user_role (user_id, role_id) VALUES (#{userId}, #{roleId})")
    int insertUserRole(@Param("userId") Long userId, @Param("roleId") Long roleId);

    @Select("SELECT id FROM sf_role WHERE role_code = #{roleCode}")
    Long selectIdByCode(@Param("roleCode") String roleCode);
}
