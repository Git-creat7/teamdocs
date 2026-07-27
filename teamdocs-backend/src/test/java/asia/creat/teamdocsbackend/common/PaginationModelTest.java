package asia.creat.teamdocsbackend.common;

import asia.creat.common.PageResult;
import asia.creat.dto.PageQuery;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PaginationModelTest {

    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

    @Test
    void pageQueryShouldUseDefaultsAndRejectOutOfRangeValues() {
        PageQuery defaults = new PageQuery();

        assertEquals(1, defaults.getCurrent());
        assertEquals(20, defaults.getSize());
        assertTrue(validator.validate(defaults).isEmpty());

        PageQuery invalid = new PageQuery();
        invalid.setCurrent(0);
        invalid.setSize(101);

        Set<String> invalidFields = validator.validate(invalid).stream()
                .map(violation -> violation.getPropertyPath().toString())
                .collect(java.util.stream.Collectors.toSet());
        assertEquals(Set.of("current", "size"), invalidFields);
    }

    @Test
    void pageResultShouldMapAllPaginationMetadata() {
        Page<String> page = new Page<>(2, 3);
        page.setRecords(List.of("four", "five", "six"));
        page.setTotal(8);

        PageResult<String> result = PageResult.from(page);

        assertEquals(List.of("four", "five", "six"), result.getRecords());
        assertEquals(8, result.getTotal());
        assertEquals(2, result.getCurrent());
        assertEquals(3, result.getSize());
        assertEquals(3, result.getPages());
    }
}
