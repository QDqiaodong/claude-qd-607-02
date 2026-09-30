package com.idc.center.controller;

import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.idc.center.common.ApiResponse;
import com.idc.center.domain.EnergyRecord;
import com.idc.center.domain.TariffVersion;
import com.idc.center.dto.Dtos;
import com.idc.center.dto.Views;
import com.idc.center.service.EnergyService;
import com.idc.center.service.TariffService;

import jakarta.validation.Valid;

/**
 * 模块五：能耗记录（抄表 → 用电量 → 分段电费）+ 电价版本链。
 */
@RestController
@RequestMapping("/api")
public class EnergyController {

    private final EnergyService energyService;
    private final TariffService tariffService;

    public EnergyController(EnergyService energyService, TariffService tariffService) {
        this.energyService = energyService;
        this.tariffService = tariffService;
    }

    @GetMapping("/energy")
    public ApiResponse<Views.PageResult<EnergyRecord>> search(Dtos.EnergyQuery query) {
        return ApiResponse.success(energyService.search(query));
    }

    /** 抄表明细详情：展开各价段日期范围 / 分摊电量 / 采用版本 / 分段费用 */
    @GetMapping("/energy/{id}")
    public ApiResponse<EnergyRecord> detail(@PathVariable Long id) {
        return ApiResponse.success(energyService.detail(id));
    }

    /** 顶部汇总卡：本月总用电 / 总电费 / 机柜用电排行 */
    @GetMapping("/energy/summary")
    public ApiResponse<Views.EnergySummary> summary(
            @RequestParam(value = "month", required = false) String month) {
        return ApiResponse.success(energyService.summary(month));
    }

    /** 新增抄表前预览：本次区间命中的电价版本与预计分段费用（不落库） */
    @PostMapping("/energy/preview")
    public ApiResponse<Views.EnergyBillingPreview> preview(@Valid @RequestBody Dtos.EnergyCreateReq req) {
        return ApiResponse.success(energyService.preview(req));
    }

    @PostMapping("/energy")
    public ApiResponse<EnergyRecord> create(@Valid @RequestBody Dtos.EnergyCreateReq req) {
        return ApiResponse.success("抄表记录已登记，分段计费快照已固化", energyService.create(req));
    }

    /** 电价版本列表 + 生效时间轴（当前 / 未来 / 历史） */
    @GetMapping("/tariffs")
    public ApiResponse<Views.TariffTimeline> tariffs() {
        return ApiResponse.success(tariffService.timeline());
    }

    /** 登记电价版本（未来版本可提前登记） */
    @PostMapping("/tariffs")
    public ApiResponse<TariffVersion> createTariff(@Valid @RequestBody Dtos.TariffVersionReq req) {
        return ApiResponse.success("电价版本已登记", tariffService.create(req));
    }

    /** 调整电价版本（已生效 / 已引用的版本只可改名称与说明） */
    @PutMapping("/tariffs/{id}")
    public ApiResponse<TariffVersion> updateTariff(@PathVariable Long id,
                                                   @Valid @RequestBody Dtos.TariffVersionReq req) {
        return ApiResponse.success("电价版本已调整", tariffService.update(id, req));
    }

    /** 删除电价版本（仅限未来生效且无抄表引用的版本） */
    @DeleteMapping("/tariffs/{id}")
    public ApiResponse<Void> deleteTariff(@PathVariable Long id) {
        tariffService.delete(id);
        return ApiResponse.success("电价版本已删除", null);
    }
}
