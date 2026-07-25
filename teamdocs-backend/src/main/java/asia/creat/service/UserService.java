package asia.creat.service;

import asia.creat.dto.UpdateProfileDTO;
import asia.creat.security.LoginUser;
import asia.creat.vo.LoginResultVO;
import asia.creat.vo.UserProfileVO;
import org.springframework.web.multipart.MultipartFile;

public interface UserService {
    void register(String username, String password);

    LoginResultVO login(String username, String password);

    void changePassword(LoginUser loginUser, String oldPassword, String newPassword);

    UserProfileVO getProfile(LoginUser loginUser);

    UserProfileVO updateProfile(LoginUser loginUser, UpdateProfileDTO dto);

    UserProfileVO updateAvatar(LoginUser loginUser, MultipartFile file);
}
