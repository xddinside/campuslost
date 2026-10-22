package com.lostfound.controller;

import com.lostfound.dto.ItemRequest;
import com.lostfound.dto.ItemResponse;
import com.lostfound.dto.StatusRequest;
import com.lostfound.model.ItemType;
import com.lostfound.model.User;
import com.lostfound.service.ItemService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/items")
public class ItemController {

    private final ItemService itemService;

    public ItemController(ItemService itemService) {
        this.itemService = itemService;
    }

    @GetMapping
    public List<ItemResponse> list(@RequestParam(required = false) ItemType type,
                                   @RequestParam(required = false) String category,
                                   @RequestParam(required = false) String location,
                                   @RequestParam(required = false) String q) {
        return itemService.list(type, category, location, q);
    }

    @GetMapping("/{id}")
    public ItemResponse get(@PathVariable Long id) {
        return itemService.get(id);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ItemResponse create(@AuthenticationPrincipal User user,
                               @Valid @RequestBody ItemRequest request) {
        return itemService.create(user, request);
    }

    @PatchMapping("/{id}/status")
    public ItemResponse updateStatus(@AuthenticationPrincipal User user,
                                     @PathVariable Long id,
                                     @Valid @RequestBody StatusRequest request) {
        return itemService.updateStatus(user, id, request.status());
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@AuthenticationPrincipal User user, @PathVariable Long id) {
        itemService.delete(user, id);
    }

    @GetMapping("/me/reported")
    public List<ItemResponse> mine(@AuthenticationPrincipal User user) {
        return itemService.mine(user);
    }
}
