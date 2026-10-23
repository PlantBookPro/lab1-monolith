package com.plantarena.tournaments.adapter.in.web;

import com.plantarena.shared.web.ApiError;
import com.plantarena.shared.webflux.TraceIdWebFilter;
import com.plantarena.tournaments.application.ActiveGlobalEntryExistsException;
import com.plantarena.tournaments.application.ClusterNotFoundException;
import com.plantarena.tournaments.application.DuplicateInvitationException;
import com.plantarena.tournaments.application.EntryNotInWindowException;
import com.plantarena.tournaments.application.GlobalEntryNotFoundException;
import com.plantarena.tournaments.application.GlobalEntryNotWithdrawableException;
import com.plantarena.tournaments.application.ImageAlreadyReservedException;
import com.plantarena.tournaments.application.InvitationNotFoundException;
import com.plantarena.tournaments.application.InvitedPlantNotFoundException;
import com.plantarena.tournaments.application.LocationRequiredException;
import com.plantarena.tournaments.application.PlantNotApprovedException;
import com.plantarena.tournaments.application.PlantNotReservableException;
import com.plantarena.tournaments.application.RateLimitExceededException;
import com.plantarena.tournaments.application.RegistrationClosedException;
import com.plantarena.tournaments.application.SelfVoteForbiddenException;
import com.plantarena.tournaments.application.SubmittedPlantNotFoundException;
import com.plantarena.tournaments.application.TagAlreadyExistsException;
import com.plantarena.tournaments.application.TagInUseException;
import com.plantarena.tournaments.application.TagNotFoundException;
import com.plantarena.tournaments.application.TournamentNotFoundException;
import com.plantarena.tournaments.application.TournamentStateConflictException;
import com.plantarena.tournaments.application.UnknownGlobalScopeException;
import com.plantarena.tournaments.application.UnknownStatusFilterException;
import com.plantarena.tournaments.application.UnknownUserException;
import com.plantarena.tournaments.application.UnknownVoteValueException;
import com.plantarena.tournaments.application.VotingClosedException;
import com.plantarena.tournaments.application.WindowNotFoundException;
import org.springframework.web.server.ServerWebExchange;
import java.net.URI;
import java.util.List;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;


@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public class TournamentsExceptionHandler {

    @ExceptionHandler(TournamentNotFoundException.class)
    public ResponseEntity<ApiError> tournamentNotFound(TournamentNotFoundException e,
                                                       ServerWebExchange exchange) {
        return respond(HttpStatus.NOT_FOUND, "TOURNAMENT_NOT_FOUND", e.getMessage(), exchange);
    }

    @ExceptionHandler(InvitationNotFoundException.class)
    public ResponseEntity<ApiError> invitationNotFound(InvitationNotFoundException e,
                                                       ServerWebExchange exchange) {
        return respond(HttpStatus.NOT_FOUND, "INVITATION_NOT_FOUND", e.getMessage(), exchange);
    }

    @ExceptionHandler(TagNotFoundException.class)
    public ResponseEntity<ApiError> tagNotFound(TagNotFoundException e,
                                                ServerWebExchange exchange) {
        return respond(HttpStatus.NOT_FOUND, "TAG_NOT_FOUND", e.getMessage(), exchange);
    }

    @ExceptionHandler(UnknownUserException.class)
    public ResponseEntity<ApiError> unknownUser(UnknownUserException e,
                                                ServerWebExchange exchange) {
        return respond(HttpStatus.NOT_FOUND, "UNKNOWN_USER", e.getMessage(), exchange);
    }

    @ExceptionHandler(InvitedPlantNotFoundException.class)
    public ResponseEntity<ApiError> plantNotFound(InvitedPlantNotFoundException e,
                                                  ServerWebExchange exchange) {
        return respond(HttpStatus.NOT_FOUND, "INVITED_PLANT_NOT_FOUND", e.getMessage(), exchange);
    }

    @ExceptionHandler(TournamentStateConflictException.class)
    public ResponseEntity<ApiError> stateConflict(TournamentStateConflictException e,
                                                  ServerWebExchange exchange) {
        return respond(HttpStatus.CONFLICT, "TOURNAMENT_STATE_CONFLICT", e.getMessage(), exchange);
    }

    @ExceptionHandler(RegistrationClosedException.class)
    public ResponseEntity<ApiError> registrationClosed(RegistrationClosedException e,
                                                       ServerWebExchange exchange) {
        return respond(HttpStatus.CONFLICT, "REGISTRATION_CLOSED", e.getMessage(), exchange);
    }

    @ExceptionHandler(DuplicateInvitationException.class)
    public ResponseEntity<ApiError> duplicateInvitation(DuplicateInvitationException e,
                                                        ServerWebExchange exchange) {
        return respond(HttpStatus.CONFLICT, "DUPLICATE_INVITATION", e.getMessage(), exchange);
    }

    @ExceptionHandler(TagAlreadyExistsException.class)
    public ResponseEntity<ApiError> tagAlreadyExists(TagAlreadyExistsException e,
                                                     ServerWebExchange exchange) {
        return respond(HttpStatus.CONFLICT, "TAG_ALREADY_EXISTS", e.getMessage(), exchange);
    }

    @ExceptionHandler(TagInUseException.class)
    public ResponseEntity<ApiError> tagInUse(TagInUseException e,
                                             ServerWebExchange exchange) {
        return respond(HttpStatus.CONFLICT, "TAG_IN_USE", e.getMessage(), exchange);
    }

    @ExceptionHandler(ImageAlreadyReservedException.class)
    public ResponseEntity<ApiError> imageAlreadyReserved(ImageAlreadyReservedException e,
                                                         ServerWebExchange exchange) {
        return respond(HttpStatus.CONFLICT, "IMAGE_ALREADY_RESERVED", e.getMessage(), exchange);
    }

    
    @ExceptionHandler(PlantNotReservableException.class)
    public ResponseEntity<ApiError> plantNotReservable(PlantNotReservableException e,
                                                       ServerWebExchange exchange) {
        String traceId = (String) exchange.getAttribute(TraceIdWebFilter.TRACE_ID_ATTRIBUTE);
        return ResponseEntity.status(HttpStatus.CONFLICT).body(new ApiError(
            URI.create("about:blank"), HttpStatus.CONFLICT.getReasonPhrase(),
            HttpStatus.CONFLICT.value(), e.getMessage(),
            exchange.getRequest().getURI(), "PLANT_NOT_RESERVABLE", List.of(), traceId,
            e.retryAt()));
    }

    @ExceptionHandler(UnknownStatusFilterException.class)
    public ResponseEntity<ApiError> unknownStatusFilter(UnknownStatusFilterException e,
                                                        ServerWebExchange exchange) {
        return respond(HttpStatus.BAD_REQUEST, "UNKNOWN_STATUS_FILTER", e.getMessage(), exchange);
    }

    @ExceptionHandler(WindowNotFoundException.class)
    public ResponseEntity<ApiError> windowNotFound(WindowNotFoundException e,
                                                   ServerWebExchange exchange) {
        return respond(HttpStatus.NOT_FOUND, "WINDOW_NOT_FOUND", e.getMessage(), exchange);
    }

    @ExceptionHandler(EntryNotInWindowException.class)
    public ResponseEntity<ApiError> entryNotInWindow(EntryNotInWindowException e,
                                                     ServerWebExchange exchange) {
        return respond(HttpStatus.NOT_FOUND, "ENTRY_NOT_IN_WINDOW", e.getMessage(), exchange);
    }

    @ExceptionHandler(VotingClosedException.class)
    public ResponseEntity<ApiError> votingClosed(VotingClosedException e,
                                                 ServerWebExchange exchange) {
        return respond(HttpStatus.CONFLICT, "VOTING_CLOSED", e.getMessage(), exchange);
    }

    @ExceptionHandler(SelfVoteForbiddenException.class)
    public ResponseEntity<ApiError> selfVoteForbidden(SelfVoteForbiddenException e,
                                                      ServerWebExchange exchange) {
        return respond(HttpStatus.FORBIDDEN, "SELF_VOTE_FORBIDDEN", e.getMessage(), exchange);
    }

    @ExceptionHandler(UnknownVoteValueException.class)
    public ResponseEntity<ApiError> unknownVoteValue(UnknownVoteValueException e,
                                                     ServerWebExchange exchange) {
        return respond(HttpStatus.BAD_REQUEST, "UNKNOWN_VOTE_VALUE", e.getMessage(), exchange);
    }

    @ExceptionHandler(SubmittedPlantNotFoundException.class)
    public ResponseEntity<ApiError> submittedPlantNotFound(SubmittedPlantNotFoundException e,
                                                           ServerWebExchange exchange) {
        return respond(HttpStatus.NOT_FOUND, "PLANT_NOT_FOUND", e.getMessage(), exchange);
    }

    @ExceptionHandler(GlobalEntryNotFoundException.class)
    public ResponseEntity<ApiError> globalEntryNotFound(GlobalEntryNotFoundException e,
                                                        ServerWebExchange exchange) {
        return respond(HttpStatus.NOT_FOUND, "GLOBAL_ENTRY_NOT_FOUND", e.getMessage(), exchange);
    }

    @ExceptionHandler(ClusterNotFoundException.class)
    public ResponseEntity<ApiError> clusterNotFound(ClusterNotFoundException e,
                                                    ServerWebExchange exchange) {
        return respond(HttpStatus.NOT_FOUND, "CLUSTER_NOT_FOUND", e.getMessage(), exchange);
    }

    @ExceptionHandler(PlantNotApprovedException.class)
    public ResponseEntity<ApiError> plantNotApproved(PlantNotApprovedException e,
                                                     ServerWebExchange exchange) {
        return respond(HttpStatus.CONFLICT, "PLANT_NOT_APPROVED", e.getMessage(), exchange);
    }

    @ExceptionHandler(LocationRequiredException.class)
    public ResponseEntity<ApiError> locationRequired(LocationRequiredException e,
                                                     ServerWebExchange exchange) {
        return respond(HttpStatus.CONFLICT, "LOCATION_REQUIRED", e.getMessage(), exchange);
    }

    @ExceptionHandler(ActiveGlobalEntryExistsException.class)
    public ResponseEntity<ApiError> activeGlobalEntryExists(ActiveGlobalEntryExistsException e,
                                                             ServerWebExchange exchange) {
        return respond(HttpStatus.CONFLICT, "GLOBAL_ENTRY_ACTIVE", e.getMessage(), exchange);
    }

    @ExceptionHandler(GlobalEntryNotWithdrawableException.class)
    public ResponseEntity<ApiError> globalEntryNotWithdrawable(
            GlobalEntryNotWithdrawableException e, ServerWebExchange exchange) {
        return respond(HttpStatus.CONFLICT, "ENTRY_IN_WINDOW", e.getMessage(), exchange);
    }

    @ExceptionHandler(UnknownGlobalScopeException.class)
    public ResponseEntity<ApiError> unknownGlobalScope(UnknownGlobalScopeException e,
                                                       ServerWebExchange exchange) {
        return respond(HttpStatus.BAD_REQUEST, "GLOBAL_SCOPE_UNKNOWN", e.getMessage(), exchange);
    }

    @ExceptionHandler(RateLimitExceededException.class)
    public ResponseEntity<ApiError> rateLimited(RateLimitExceededException e,
                                                ServerWebExchange exchange) {
        String traceId = (String) exchange.getAttribute(TraceIdWebFilter.TRACE_ID_ATTRIBUTE);
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
            .header(HttpHeaders.RETRY_AFTER, String.valueOf(e.retryAfterSeconds()))
            .body(new ApiError(URI.create("about:blank"), HttpStatus.TOO_MANY_REQUESTS.getReasonPhrase(),
                HttpStatus.TOO_MANY_REQUESTS.value(), e.getMessage(),
                exchange.getRequest().getURI(), "RATE_LIMITED", List.of(), traceId));
    }

    private ResponseEntity<ApiError> respond(HttpStatus status, String code, String detail,
                                             ServerWebExchange exchange) {
        String traceId = (String) exchange.getAttribute(TraceIdWebFilter.TRACE_ID_ATTRIBUTE);
        return ResponseEntity.status(status).body(new ApiError(
            URI.create("about:blank"), status.getReasonPhrase(), status.value(), detail,
            exchange.getRequest().getURI(), code, List.of(), traceId));
    }
}
