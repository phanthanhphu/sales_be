package org.bsl.cartonloading.controller;

import org.bsl.cartonloading.dto.BomReviewDecisionRequest;
import org.bsl.cartonloading.model.BomDocument;
import org.bsl.cartonloading.model.MprBomReview;
import org.bsl.cartonloading.service.MprBomReviewService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** BOM-side workflow for approving or returning Sales MPR corrections. */
@RestController
@RequestMapping("/api/boms/{bomId}/mpr-reviews")
public class BomMprReviewController {
    private final MprBomReviewService reviewService;

    public BomMprReviewController(MprBomReviewService reviewService) {
        this.reviewService = reviewService;
    }

    @GetMapping
    public List<MprBomReview> list(@PathVariable String bomId) {
        return reviewService.listForBom(bomId);
    }

    @PostMapping("/{reviewId}/apply")
    public BomDocument apply(
            @PathVariable String bomId,
            @PathVariable String reviewId,
            @RequestBody(required = false) BomReviewDecisionRequest request
    ) {
        return reviewService.applyToBom(bomId, reviewId, request);
    }

    @PostMapping("/{reviewId}/recheck")
    public MprBomReview recheck(
            @PathVariable String bomId,
            @PathVariable String reviewId,
            @RequestBody(required = false) BomReviewDecisionRequest request
    ) {
        return reviewService.sendBackToSales(bomId, reviewId, request);
    }
}
