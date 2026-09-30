package com.idc.center.controller;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.idc.center.common.ApiResponse;
import com.idc.center.domain.EnergyPriceVersion;
import com.idc.center.dto.Dtos;
import com.idc.center.dto.Views;
import com.idc.center.service.EnergyService;

import jakarta.validation.Valid;

/**
 * 模块五：能耗记录（电价版本 → 抄表分段计费 → 月度汇总）。
 */
@RestController
@RequestMapping("/api")
public class EnergyController {

    private final EnergyService energyService;

    public EnergyController(EnergyService energyService) {
        this.energyService = energyService;
    }

    @GetMapping("/energy")
    public ApiResponse<Views.PageResult<Views.EnergyRecordView>> search(Dtos.EnergyQuery query) {
        return ApiResponse.success(energyService.search(query));
    }

    /** 顶部汇总卡：本月总用电 / 总电费 / 机柜用电排行 */
    @GetMapping("/energy/summary")
    public ApiResponse<Views.EnergySummary> summary(
            @RequestParam(value = "month", required = false) String month) {
        return ApiResponse.success(energyService.summary(month));
    }

    @GetMapping("/energy/price-versions")
    public ApiResponse<List<Views.EnergyPriceVersionView>> priceVersions() {
        return ApiResponse.success(energyService.priceVersions());
    }

    @GetMapping("/energy/preview")
    public ApiResponse<Views.EnergyBillingPreview> preview(
            @RequestParam Long cabinetId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate meterDate,
            @RequestParam BigDecimal meterReading) {
        Dtos.EnergyCreateReq req = new Dtos.EnergyCreateReq();
        req.setCabinetId(cabinetId);
        req.setMeterDate(meterDate);
        req.setMeterReading(meterReading);
        return ApiResponse.success(energyService.preview(req));
    }

    @PostMapping("/energy/price-versions")
    public ApiResponse<EnergyPriceVersion> createPriceVersion(
            @Valid @RequestBody Dtos.EnergyPriceVersionReq req) {
        return ApiResponse.success("电价版本已登记", energyService.createPriceVersion(req));
    }

    @PostMapping("/energy")
    public ApiResponse<Views.EnergyRecordView> create(@Valid @RequestBody Dtos.EnergyCreateReq req) {
        return ApiResponse.success("抄表记录已登记，分段计费快照已固化", energyService.create(req));
    }
}
