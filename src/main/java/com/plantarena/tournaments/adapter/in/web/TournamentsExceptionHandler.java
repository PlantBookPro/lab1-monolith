package com.plantarena.tournaments.adapter.in.web;

import com.plantarena.shared.web.ApiError;
import com.plantarena.shared.web.TraceIdFilter;
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
import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.util.List;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Перевод исключений tournaments в ProblemDetail-подобное тело (раздел 13:
 * скрытое — 404, конфликты состояний/дедлайна/дублей — 409, неизвестный
 * фильтр — 400). PLANT_NOT_RESERVABLE несёт retryAt — срок временного
 * запрета изображения (COOLDOWN), у остальных — null.
 *
 * <p>HIGHEST_PRECEDENCE: advice без @Order сортируются по порядку сканирования,
 * и catch-all Exception из shared ApiExceptionHandler успевает перехватить
 * исключения tournaments раньше (пакет shared идёт после plants/identity/media,
 * но перед tournaments) — явный приоритет ставит контекстный перевод первым.
 */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public class TournamentsExceptionHandler {

    @ExceptionHandler(TournamentNotFoundException.class)
    public ResponseEntity<ApiError> tournamentNotFound(TournamentNotFoundException e,
                                                       HttpServletRequest request) {
        return respond(HttpStatus.NOT_FOUND, "TOURNAMENT_NOT_FOUND", e.getMessage(), request);
    }

    @ExceptionHandler(InvitationNotFoundException.class)
    public ResponseEntity<ApiError> invitationNotFound(InvitationNotFoundException e,
                                                       HttpServletRequest request) {
        return respond(HttpStatus.NOT_FOUND, "INVITATION_NOT_FOUND", e.getMessage(), request);
    }

    @ExceptionHandler(TagNotFoundException.class)
    public ResponseEntity<ApiError> tagNotFound(TagNotFoundException e,
                                                HttpServletRequest request) {
        return respond(HttpStatus.NOT_FOUND, "TAG_NOT_FOUND", e.getMessage(), request);
    }

    @ExceptionHandler(UnknownUserException.class)
    public ResponseEntity<ApiError> unknownUser(UnknownUserException e,
                                                HttpServletRequest request) {
        return respond(HttpStatus.NOT_FOUND, "UNKNOWN_USER", e.getMessage(), request);
    }

    @ExceptionHandler(InvitedPlantNotFoundException.class)
    public ResponseEntity<ApiError> plantNotFound(InvitedPlantNotFoundException e,
                                                  HttpServletRequest request) {
        return respond(HttpStatus.NOT_FOUND, "INVITED_PLANT_NOT_FOUND", e.getMessage(), request);
    }

    @ExceptionHandler(TournamentStateConflictException.class)
    public ResponseEntity<ApiError> stateConflict(TournamentStateConflictException e,
                                                  HttpServletRequest request) {
        return respond(HttpStatus.CONFLICT, "TOURNAMENT_STATE_CONFLICT", e.getMessage(), request);
    }

    @ExceptionHandler(RegistrationClosedException.class)
    public ResponseEntity<ApiError> registrationClosed(RegistrationClosedException e,
                                                       HttpServletRequest request) {
        return respond(HttpStatus.CONFLICT, "REGISTRATION_CLOSED", e.getMessage(), request);
    }

    @ExceptionHandler(DuplicateInvitationException.class)
    public ResponseEntity<ApiError> duplicateInvitation(DuplicateInvitationException e,
                                                        HttpServletRequest request) {
        return respond(HttpStatus.CONFLICT, "DUPLICATE_INVITATION", e.getMessage(), request);
    }

    @ExceptionHandler(TagAlreadyExistsException.class)
    public ResponseEntity<ApiError> tagAlreadyExists(TagAlreadyExistsException e,
                                                     HttpServletRequest request) {
        return respond(HttpStatus.CONFLICT, "TAG_ALREADY_EXISTS", e.getMessage(), request);
    }

    @ExceptionHandler(TagInUseException.class)
    public ResponseEntity<ApiError> tagInUse(TagInUseException e,
                                             HttpServletRequest request) {
        return respond(HttpStatus.CONFLICT, "TAG_IN_USE", e.getMessage(), request);
    }

    @ExceptionHandler(ImageAlreadyReservedException.class)
    public ResponseEntity<ApiError> imageAlreadyReserved(ImageAlreadyReservedException e,
                                                         HttpServletRequest request) {
        return respond(HttpStatus.CONFLICT, "IMAGE_ALREADY_RESERVED", e.getMessage(), request);
    }

    /** 409 + retryAt: срок истечения временного запрета изображения (null — бессрочный/нет). */
    @ExceptionHandler(PlantNotReservableException.class)
    public ResponseEntity<ApiError> plantNotReservable(PlantNotReservableException e,
                                                       HttpServletRequest request) {
        String traceId = (String) request.getAttribute(TraceIdFilter.TRACE_ID_ATTRIBUTE);
        return ResponseEntity.status(HttpStatus.CONFLICT).body(new ApiError(
            URI.create("about:blank"), HttpStatus.CONFLICT.getReasonPhrase(),
            HttpStatus.CONFLICT.value(), e.getMessage(),
            URI.create(request.getRequestURI()), "PLANT_NOT_RESERVABLE", List.of(), traceId,
            e.retryAt()));
    }

    @ExceptionHandler(UnknownStatusFilterException.class)
    public ResponseEntity<ApiError> unknownStatusFilter(UnknownStatusFilterException e,
                                                        HttpServletRequest request) {
        return respond(HttpStatus.BAD_REQUEST, "UNKNOWN_STATUS_FILTER", e.getMessage(), request);
    }

    @ExceptionHandler(WindowNotFoundException.class)
    public ResponseEntity<ApiError> windowNotFound(WindowNotFoundException e,
                                                   HttpServletRequest request) {
        return respond(HttpStatus.NOT_FOUND, "WINDOW_NOT_FOUND", e.getMessage(), request);
    }

    @ExceptionHandler(EntryNotInWindowException.class)
    public ResponseEntity<ApiError> entryNotInWindow(EntryNotInWindowException e,
                                                     HttpServletRequest request) {
        return respond(HttpStatus.NOT_FOUND, "ENTRY_NOT_IN_WINDOW", e.getMessage(), request);
    }

    @ExceptionHandler(VotingClosedException.class)
    public ResponseEntity<ApiError> votingClosed(VotingClosedException e,
                                                 HttpServletRequest request) {
        return respond(HttpStatus.CONFLICT, "VOTING_CLOSED", e.getMessage(), request);
    }

    @ExceptionHandler(SelfVoteForbiddenException.class)
    public ResponseEntity<ApiError> selfVoteForbidden(SelfVoteForbiddenException e,
                                                      HttpServletRequest request) {
        return respond(HttpStatus.FORBIDDEN, "SELF_VOTE_FORBIDDEN", e.getMessage(), request);
    }

    @ExceptionHandler(UnknownVoteValueException.class)
    public ResponseEntity<ApiError> unknownVoteValue(UnknownVoteValueException e,
                                                     HttpServletRequest request) {
        return respond(HttpStatus.BAD_REQUEST, "UNKNOWN_VOTE_VALUE", e.getMessage(), request);
    }

    @ExceptionHandler(SubmittedPlantNotFoundException.class)
    public ResponseEntity<ApiError> submittedPlantNotFound(SubmittedPlantNotFoundException e,
                                                           HttpServletRequest request) {
        return respond(HttpStatus.NOT_FOUND, "PLANT_NOT_FOUND", e.getMessage(), request);
    }

    @ExceptionHandler(GlobalEntryNotFoundException.class)
    public ResponseEntity<ApiError> globalEntryNotFound(GlobalEntryNotFoundException e,
                                                        HttpServletRequest request) {
        return respond(HttpStatus.NOT_FOUND, "GLOBAL_ENTRY_NOT_FOUND", e.getMessage(), request);
    }

    @ExceptionHandler(ClusterNotFoundException.class)
    public ResponseEntity<ApiError> clusterNotFound(ClusterNotFoundException e,
                                                    HttpServletRequest request) {
        return respond(HttpStatus.NOT_FOUND, "CLUSTER_NOT_FOUND", e.getMessage(), request);
    }

    @ExceptionHandler(PlantNotApprovedException.class)
    public ResponseEntity<ApiError> plantNotApproved(PlantNotApprovedException e,
                                                     HttpServletRequest request) {
        return respond(HttpStatus.CONFLICT, "PLANT_NOT_APPROVED", e.getMessage(), request);
    }

    @ExceptionHandler(LocationRequiredException.class)
    public ResponseEntity<ApiError> locationRequired(LocationRequiredException e,
                                                     HttpServletRequest request) {
        return respond(HttpStatus.CONFLICT, "LOCATION_REQUIRED", e.getMessage(), request);
    }

    @ExceptionHandler(ActiveGlobalEntryExistsException.class)
    public ResponseEntity<ApiError> activeGlobalEntryExists(ActiveGlobalEntryExistsException e,
                                                             HttpServletRequest request) {
        return respond(HttpStatus.CONFLICT, "GLOBAL_ENTRY_ACTIVE", e.getMessage(), request);
    }

    @ExceptionHandler(GlobalEntryNotWithdrawableException.class)
    public ResponseEntity<ApiError> globalEntryNotWithdrawable(
            GlobalEntryNotWithdrawableException e, HttpServletRequest request) {
        return respond(HttpStatus.CONFLICT, "ENTRY_IN_WINDOW", e.getMessage(), request);
    }

    @ExceptionHandler(UnknownGlobalScopeException.class)
    public ResponseEntity<ApiError> unknownGlobalScope(UnknownGlobalScopeException e,
                                                       HttpServletRequest request) {
        return respond(HttpStatus.BAD_REQUEST, "GLOBAL_SCOPE_UNKNOWN", e.getMessage(), request);
    }

    private ResponseEntity<ApiError> respond(HttpStatus status, String code, String detail,
                                             HttpServletRequest request) {
        String traceId = (String) request.getAttribute(TraceIdFilter.TRACE_ID_ATTRIBUTE);
        return ResponseEntity.status(status).body(new ApiError(
            URI.create("about:blank"), status.getReasonPhrase(), status.value(), detail,
            URI.create(request.getRequestURI()), code, List.of(), traceId));
    }
}
