package asia.creat.controller;
import asia.creat.common.Result;
import asia.creat.security.LoginUser;
import asia.creat.service.DocumentIndexService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/spaces/{spaceId}/documents/{documentId}/index")
@RequiredArgsConstructor
public class DocumentIndexController {
    private final DocumentIndexService indexes;
    @GetMapping
    public Result status(@PathVariable Long spaceId, @PathVariable Long documentId, @AuthenticationPrincipal LoginUser user) {
        return Result.success(indexes.status(spaceId, documentId, user));
    }
    @PostMapping("/{target}/repair")
    public Result repair(@PathVariable Long spaceId, @PathVariable Long documentId, @PathVariable String target,
                         @AuthenticationPrincipal LoginUser user) {
        return Result.success(indexes.repair(spaceId, documentId, target, user));
    }
}
