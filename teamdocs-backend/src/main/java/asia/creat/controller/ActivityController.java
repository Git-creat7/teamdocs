package asia.creat.controller;

import asia.creat.common.Result;
import asia.creat.security.LoginUser;
import asia.creat.service.OperationLogService;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/activities")
public class ActivityController {
    private final OperationLogService operationLogService;

    public ActivityController(OperationLogService operationLogService) {
        this.operationLogService = operationLogService;
    }

    /*
     * 我所在空间的最近操作动态 (团队动态流)
     * */
    @GetMapping("")
    public Result listRecentActivities(@RequestParam(value = "spaceId", required = false) Long spaceId,
                                       @RequestParam(value = "limit", required = false) Integer limit,
                                       @AuthenticationPrincipal LoginUser loginUser) {
        return Result.success(operationLogService.listRecentActivities(loginUser.getUserId(), spaceId, limit));
    }
}
