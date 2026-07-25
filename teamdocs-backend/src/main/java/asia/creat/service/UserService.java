package asia.creat.service;

import asia.creat.security.LoginUser;

public interface UserService {
    void register(String username, String password);

    String login(String username, String password);

    void changePassword(LoginUser loginUser, String oldPassword, String newPassword);
}