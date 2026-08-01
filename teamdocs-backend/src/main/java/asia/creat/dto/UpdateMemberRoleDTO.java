package asia.creat.dto;

import asia.creat.entity.SpaceRole;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import jakarta.validation.constraints.NotNull;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class UpdateMemberRoleDTO {
    @NotNull(message = "角色不能为空")
    private SpaceRole role;
}