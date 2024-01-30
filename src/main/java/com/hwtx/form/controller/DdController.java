package com.hwtx.form.controller;

import com.hwtx.form.domain.def.Option;
import com.hwtx.form.domain.def.Select;
import com.hwtx.form.domain.service.FormService;
import io.geekidea.boot.framework.response.ApiCode;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.compress.utils.Lists;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import javax.annotation.Resource;
import java.util.List;

@Slf4j
@RestController
@Tag(name = "测试示例")
@RequestMapping("/demo")
public class DdController {

    @Resource
    private FormService formService;

    @GetMapping("/options")
    @Operation(summary = "Css可选项")
    public Api2Result load(@RequestParam Long formId) throws Exception {
        Select select = new Select();
        List<Option> options = Lists.newArrayList();
        Option option = new Option();
        option.setLabel("A");
        option.setValue("1");
        options.add(option);

        option = new Option();
        option.setLabel("B");
        option.setValue("2");
        options.add(option);

        option = new Option();
        option.setLabel("C");
        option.setValue("3");
        options.add(option);
        select.setOptions(options);
        return Api2Result.result(ApiCode.SUCCESS, "加载成功", select);
    }
}
