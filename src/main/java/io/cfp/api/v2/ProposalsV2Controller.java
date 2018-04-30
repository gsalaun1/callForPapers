/*
 * Copyright (c) 2016 BreizhCamp
 * [http://breizhcamp.org]
 *
 * This file is part of CFP.io.
 *
 * CFP.io is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */

package io.cfp.api.v2;

import com.itextpdf.text.DocumentException;
import io.cfp.api.CFPMediaType;
import io.cfp.domain.exception.BadRequestException;
import io.cfp.domain.exception.CospeakerNotFoundException;
import io.cfp.domain.exception.ForbiddenException;
import io.cfp.domain.exception.NotFoundException;
import io.cfp.dto.user.CospeakerProfil;
import io.cfp.entity.Role;
import io.cfp.mapper.CoSpeakerMapper;
import io.cfp.mapper.ProposalMapper;
import io.cfp.mapper.RateMapper;
import io.cfp.mapper.UserMapper;
import io.cfp.model.Proposal;
import io.cfp.model.Proposals;
import io.cfp.model.Rate;
import io.cfp.model.User;
import io.cfp.model.queries.ProposalQuery;
import io.cfp.model.queries.RateQuery;
import io.cfp.multitenant.TenantId;
import io.cfp.service.PdfCardService;
import io.cfp.service.email.EmailingService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.hateoas.Link;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.annotation.Secured;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import javax.servlet.http.HttpServletResponse;
import javax.validation.Valid;
import java.io.IOException;
import java.util.*;
import java.util.stream.Collectors;

import static io.cfp.entity.Role.*;
import static org.springframework.hateoas.mvc.ControllerLinkBuilder.methodOn;

@RestController
@RequestMapping(value = "/api/proposals", produces = CFPMediaType.APPLICATION_VND_CFP_IO_V2_VALUE)
public class ProposalsController {

    private static final Logger LOGGER = LoggerFactory.getLogger(ProposalsController.class);

    @Autowired
    private ProposalMapper proposalMapper;

    @Autowired
    private RateMapper rates;

    @Autowired
    private CoSpeakerMapper cospeakers;

    @Autowired
    private UserMapper users;

    @Autowired
    private EmailingService emailingService;

    @Autowired
    private PdfCardService pdfCardService;

    @GetMapping
    @Secured({REVIEWER, ADMIN})
    public Proposals search(@AuthenticationPrincipal User user,
                                 @TenantId String event,
                                 @RequestParam(name = "states", required = false) String states,
                                 @RequestParam(name = "userId", required = false) Integer userId,
                                 @RequestParam(name = "page", required = false, defaultValue = "0") Integer page,
                                 @RequestParam(name = "size", required = false, defaultValue = "20") Integer size,
                                 @RequestParam(name = "sort", required = false, defaultValue = "added") String sort,
                                 @RequestParam(name = "order", required = false, defaultValue = "asc") String order,
                                 HttpServletResponse response) {

        if (size == 0) {
            size = 1;
        }

        List<Proposal.State> stateList = new ArrayList<>();
        if (states != null) {
            stateList = Arrays.stream(states.split(","))
                .map(Proposal.State::valueOf)
                .collect(Collectors.toList());
        }

        ProposalQuery query = new ProposalQuery()
            .setEventId(event)
            .setStates(stateList)
            .setUserId(userId)
            .setPage(page)
            .setSize(size)
            .setSort(sort)
            .setOrder(order.equalsIgnoreCase("desc")?"desc":"asc");

        LOGGER.info("Search Proposals : {}", query);
        List<Proposal> p = proposalMapper.findAll(query);
        LOGGER.debug("Found {} Proposals", p.size());

        int totalElements = proposalMapper.count(new ProposalQuery());

        Proposals proposals = new Proposals()
            .setPage(page)
            .setProposals(p)
            .setTotalElements(totalElements);

        int totalPages = totalElements / size;

        Integer nextPage = page < totalPages ? page +1 : page;
        Integer prevPage = page > 0 ? page -1 : 0;

        StringJoiner links = new StringJoiner(", ");

        if (hasNextPage(page, totalPages)) {
            Link next = linkTo(methodOn(ProposalsV2Controller.class).search(user, event, states, userId, nextPage, size, sort, order, response)).withRel("next").expand();
            links.add(next.toString());
        }
        if (hasFirstPage(page)) {
            Link first = linkTo(methodOn(ProposalsV2Controller.class).search(user, event, states, userId,0, size, sort, order, response)).withRel("first").expand();
            links.add(first.toString());
        }
        if (hasPreviousPage(page)) {
            Link prev = linkTo(methodOn(ProposalsV2Controller.class).search(user, event, states, userId, prevPage, size, sort, order, response)).withRel("prev").expand();
            links.add(prev.toString());
        }
        if (hasLastPage(page, totalPages)) {
            Link last = linkTo(methodOn(ProposalsV2Controller.class).search(user, event, states, userId, totalPages, size, sort, order, response)).withRel("last").expand();
            links.add(last.toString());
        }

        if (links.length() > 0) {
            response.addHeader(HttpHeaders.LINK, links.toString());
        }

        for (Proposal proposal : p) {
            List<String> emails = new ArrayList<>();
            float total = 0;
            int votes = 0;
            for (Rate rate : rates.findAll(new RateQuery().setProposalId(proposal.getId()))) {
                emails.add(rate.getUser().getEmail());
                if (rate.getRate() > 0) {
                    total += rate.getRate();
                    votes++;
                }
            }
            proposal.setVoteUsersEmail(emails);
            if (votes > 0) {
                proposal.setMean(String.valueOf(total/votes));
            }
        }

        return proposals;
    }


    final boolean hasNextPage(final int page, final long totalPages) {
        return page < totalPages;
    }

    final boolean hasPreviousPage(final int page) {
        return page > 0;
    }

    final boolean hasFirstPage(final int page) {
        return hasPreviousPage(page);
    }

    final boolean hasLastPage(final int page, final long totalPages) {
        return totalPages > 1 && hasNextPage(page, totalPages);
    }




    @GetMapping("{id}")
    @Secured({AUTHENTICATED})
    public Proposal get(@AuthenticationPrincipal User user,
                        @TenantId String event,
                        @PathVariable Integer id) {
        LOGGER.info("Get Proposal with id {}", id);
        Proposal proposal = proposalMapper.findById(id, event);

        if (proposal == null) {
            throw new NotFoundException();
        }

        if (!user.hasRole(REVIEWER)
            && !user.hasRole(ADMIN)
            && user.getId() != proposal.getSpeaker().getId()) {
            throw new ForbiddenException("Vous n'êtes pas autorisé à accéder au Proposal "+id);
        }

        LOGGER.debug("Found Proposal {}", proposal);
        return proposal;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Secured(AUTHENTICATED)
    @Transactional
    public Proposal create(@TenantId String event,
                           @AuthenticationPrincipal User user,
                           @Valid @RequestBody Proposal proposal) {
        LOGGER.info("User {} create a proposal : {}", user.getId(), proposal.getName());
        // FIXME manage drfat state client side without use of /drafts API
        proposal.setEventId(event);

        if (proposal.getSpeaker() == null) {
            proposal.setSpeaker(user);
        }

        // A user can only create proposalMapper for himself
        if (!user.hasRole(ADMIN)
            && user.getId() != proposal.getSpeaker().getId()) {
            throw new BadRequestException();
        }

        proposal.setState(Proposal.State.DRAFT) // when created, a talk is a Draft. Need to be confirmed
                .setAdded(new Date());
        proposalMapper.insert(proposal);

        createCospeakers(proposal);

        return proposal;
    }

    private void createCospeakers(Proposal proposal) {
        cospeakers.delete(proposal.getId());
        if (proposal.getCospeakers() != null) {
            for (User cs : proposal.getCospeakers()) {
                final User cospeaker = users.findByEmail(cs.getEmail());
                if (cospeaker == null) throw new CospeakerNotFoundException(new CospeakerProfil(cs.getEmail()));
                cospeakers.insert(proposal.getId(), cospeaker.getId());
            }
        }
    }

    @PutMapping("{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Secured(AUTHENTICATED)
    @Transactional
    public void update(@AuthenticationPrincipal User user,
                       @TenantId String event,
                       @PathVariable Integer id,
                       @Valid @RequestBody Proposal proposal) {

        if (proposal.getSpeaker() == null) {
            proposal.setSpeaker(user);
        }

        // A user can't change proposal's speaker
        if (!user.hasRole(ADMIN)
            && user.getId() != proposal.getSpeaker().getId()) {
            throw new ForbiddenException("Vous n'êtes pas autorisé à modifier le Proposal "+id);
        }
        proposal.setId(id);
        LOGGER.info("User {} update the proposal {}", user.getId(), proposal.getName());

        // A non-admin user can only update his proposalMapper
        Integer userId = !user.hasRole(ADMIN) ? user.getId() : null;
        proposalMapper.updateForEvent(proposal, event, userId);

        createCospeakers(proposal);
    }

    @DeleteMapping("{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Secured(ADMIN)
    public void delete(@AuthenticationPrincipal User user,
                       @TenantId String event,
                       @PathVariable Integer id) {
        LOGGER.info("User {} delete the Proposal {}", user.getId(), id);
        proposalMapper.deleteForEvent(id, event);
    }

    /**
     * Delete all sessions (aka reset CFP)
     */
    @DeleteMapping
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Secured(Role.ADMIN)
    public void deleteAll(@TenantId String event) {
        proposalMapper.deleteAllByEventId(event);
    }


    @PutMapping("{id}/confirm")
    @Secured(AUTHENTICATED)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void confirm(@AuthenticationPrincipal User user,
                        @TenantId String event,
                        @PathVariable int id) {

        LOGGER.info("Proposal {} change state to CONFIRMED", id);

        Proposal proposal = proposalMapper.findById(id, event);
        proposal.setId(id);
        proposal.setEventId(event);
        proposal.setState(Proposal.State.CONFIRMED);

        //FIXME check proposal is in DRAFT state
        proposalMapper.updateState(proposal);

        emailingService.sendConfirmed(user, proposal);
    }

    @PutMapping("{id}/confirmPresence")
    @Secured(AUTHENTICATED)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void confirmPresence(@AuthenticationPrincipal User user,
                        @TenantId String event,
                        @PathVariable int id) {

        LOGGER.info("Proposal {} change state to CONFIRMED_PRESENCE", id);

        Proposal proposal = proposalMapper.findById(id, event);
        proposal.setId(id);
        proposal.setEventId(event);
        proposal.setState(Proposal.State.PRESENT);

        proposalMapper.updateState(proposal);

        emailingService.sendConfirmedPresence(user, proposal);
    }


    @PutMapping("{id}/accept")
    @Secured(ADMIN)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void accept(@TenantId String event,
                       @PathVariable int id) {

        LOGGER.info("Proposal {} change state to ACCEPTED", id);
        Proposal proposal = proposalMapper.findById(id, event);
        proposal.setId(id);
        proposal.setEventId(event);
        proposal.setState(Proposal.State.ACCEPTED);

        proposalMapper.updateState(proposal);
    }

    @PutMapping("{id}/backup")
    @Secured(ADMIN)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void backup(@TenantId String event,
                       @PathVariable int id) {
        LOGGER.info("Proposal {} change state to BACKUP", id);
        Proposal proposal = proposalMapper.findById(id, event);
        proposal.setId(id);
        proposal.setEventId(event);
        proposal.setState(Proposal.State.BACKUP);

        proposalMapper.updateState(proposal);
    }

    @PutMapping("{id}/reject")
    @Secured(ADMIN)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void reject(@TenantId String event,
                       @PathVariable int id) {
        LOGGER.info("Proposal {} change state to REJECT", id);
        Proposal proposal = proposalMapper.findById(id, event);
        proposal.setId(id);
        proposal.setEventId(event);
        proposal.setState(Proposal.State.REFUSED);

        proposalMapper.updateState(proposal);
    }

    @PutMapping("{id}/retract")
    @Secured(ADMIN)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void retract(@TenantId String event,
                        @PathVariable int id) {
        LOGGER.info("Proposal {} change state to CONFIRMED", id);
        Proposal proposal = proposalMapper.findById(id, event);
        proposal.setId(id);
        proposal.setEventId(event);
        proposal.setState(Proposal.State.CONFIRMED);

        proposalMapper.updateState(proposal);
    }

    @PutMapping("rejectOthers")
    @Secured(ADMIN)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void rejectOthers(@TenantId String event) {
        LOGGER.info("All CONFIRMED Proposals change state to REJECT");
        int updatedProposals = proposalMapper.updateAllStateWhere(event, Proposal.State.REFUSED, Proposal.State.CONFIRMED);
        LOGGER.info("{} Proposals have changed to REJECT", updatedProposals);
    }

    /**
     * Add a new rating
     */
    @PostMapping("{proposalId}/rates")
    @Secured({REVIEWER, ADMIN})
    public Rate addRate(@PathVariable int proposalId,
                        @AuthenticationPrincipal User user,
                        @Valid @RequestBody Rate rate,
                        @TenantId String eventId) {
        rate.setEventId(eventId);
        rate.setUser(user);
        rate.setTalk(new Proposal().setId(proposalId));
        rate.setAdded(new Date());
        rates.insert(rate);
        return rate;
    }

    /**
     * Edit a rating
     */
    @PutMapping("{proposalId}/rates/{rateId}")
    @Secured({REVIEWER, ADMIN})
    public Rate update(@PathVariable int proposalId,
                       @PathVariable int rateId,
                       @AuthenticationPrincipal User user,
                       @Valid @RequestBody Rate rate,
                       @TenantId String eventId) {
        rate.setId(rateId);
        rate.setUser(user);
        rate.setEventId(eventId);
        rate.setTalk(new Proposal().setId(proposalId));
        rate.setAdded(new Date());
        rates.update(rate);
        return rate;
    }


    /**
     * Get a specific rating
     */
    @GetMapping("{proposalId}/rates")
    @Secured(Role.ADMIN)
    public List<Rate> getRate(@PathVariable int proposalId,
                              @TenantId String eventId) {
        RateQuery rateQuery = new RateQuery()
                                    .setEventId(eventId)
                                    .setProposalId(proposalId);
        return rates.findAll(rateQuery);
    }

    /**
     * Get a specific rating
     */
    @GetMapping("{proposalId}/rates/me")
    @Secured({REVIEWER, ADMIN})
    public Rate getMyRate(@PathVariable int proposalId,
                        @AuthenticationPrincipal User user,
                        @TenantId String eventId) {
        return rates.findMyRate(proposalId, user.getId(), eventId);
    }

    @GetMapping(path = "export/cards.pdf", produces = "application/pdf")
    @Secured(ADMIN)
    public void exportPdf(@TenantId String eventId,
                          HttpServletResponse response) throws IOException, DocumentException {
        response.addHeader(HttpHeaders.CONTENT_TYPE, "application/pdf");
        pdfCardService.export(eventId, response.getOutputStream());
    }


}