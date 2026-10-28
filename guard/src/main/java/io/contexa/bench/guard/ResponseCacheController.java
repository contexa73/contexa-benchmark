package io.contexa.bench.guard;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/** What the response records did since the start: hits, misses and the tokens the hits saved, per layer. */
@RestController
public class ResponseCacheController {

    @GetMapping("/guard/cache")
    public Map<String, List<CachingChatModel.Stats>> cache() {
        return Map.of("layers", ResponseCacheConfiguration.stats());
    }
}
