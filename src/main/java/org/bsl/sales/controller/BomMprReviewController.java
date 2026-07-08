package org.bsl.sales.controller;

import org.bsl.sales.dto.BomReviewDecisionRequest;
import org.bsl.sales.model.BomDocument;
import org.bsl.sales.model.MprBomReview;
import org.bsl.sales.service.MprBomReviewService;
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
