package io.github.hectorvent.floci.lifecycle;

import io.github.hectorvent.floci.core.common.AccountLifecycleService;
import jakarta.inject.Inject;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.List;
import java.util.Map;

/**
 * Admin surface for the test-isolation clone/clear feature: give each test its own throwaway
 * account cloned from a shared baseline, then wipe it. Internal to Floci — not part of any AWS
 * API emulation, hence the plain JSON error body rather than an AWS-shaped one.
 */
@Path("{prefix:(_floci|_localstack)}")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class AccountLifecycleController {

    private final AccountLifecycleService accountLifecycleService;

    @Inject
    public AccountLifecycleController(AccountLifecycleService accountLifecycleService) {
        this.accountLifecycleService = accountLifecycleService;
    }

    public record CloneRequest(String source, List<String> services) {}

    public record ClearRequest(List<String> services) {}

    @POST
    @Path("/accounts/{accountId}/clone")
    public Response clone(@PathParam("accountId") String accountId, CloneRequest request) {
        if (request == null || request.source() == null || request.source().isBlank()) {
            return badRequest("source is required");
        }
        try {
            accountLifecycleService.cloneAccount(accountId, request.source(), request.services());
        } catch (IllegalArgumentException e) {
            return badRequest(e.getMessage());
        }
        return Response.ok(Map.of("status", "OK")).build();
    }

    @POST
    @Path("/accounts/{accountId}/clear")
    public Response clear(@PathParam("accountId") String accountId, ClearRequest request) {
        try {
            accountLifecycleService.clearAccount(accountId, request == null ? null : request.services());
        } catch (IllegalArgumentException e) {
            return badRequest(e.getMessage());
        }
        return Response.ok(Map.of("status", "OK")).build();
    }

    private Response badRequest(String message) {
        return Response.status(Response.Status.BAD_REQUEST).entity(Map.of("error", message)).build();
    }
}
