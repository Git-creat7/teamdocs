package asia.creat.service;

import asia.creat.dto.UpdateProfileDTO;
import asia.creat.security.LoginUser;
import asia.creat.vo.UserProfileVO;

public interface UserService {
    void register(String username, String password);

    String login(String username, String password);

    void changePassword(LoginUser loginUser, String oldPassword, String newPassword);

    UserProfileVO getProfile(LoginUser loginUser);

    UserProfileVO updateProfile(LoginUser loginUser, UpdateProfileDTO dto);
}
