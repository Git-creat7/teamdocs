package asia.creat.service;

import jakarta.validation.constraints.NotBlank;

public interface UserService {
    public void register(String username, String password);

    public String login(String username, String password);
}