package asia.creat.common;

import lombok.Data;

@Data
public class Result {
    private int code;
    private String msg;
    private Object data;

    public Result() {
    }

    public static Result success() {
        Result res = new Result();
        res.code = 1;
        res.msg = "success";
        return res;
    }

    public static Result success(Object data) {
        Result res = new Result();
        res.data = data;
        res.code = 1;
        res.msg = "success";
        return res;
    }

    public static Result error(String msg) {
        Result res = new Result();
        res.msg = msg;
        res.code = 0;
        return res;
    }

}
