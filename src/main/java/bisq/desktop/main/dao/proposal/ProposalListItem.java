/*
 * This file is part of Bisq.
 *
 * Bisq is free software: you can redistribute it and/or modify it
 * under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or (at
 * your option) any later version.
 *
 * Bisq is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE. See the GNU Affero General Public
 * License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with Bisq. If not, see <http://www.gnu.org/licenses/>.
 */

package bisq.desktop.main.dao.proposal;

import bisq.desktop.components.AutoTooltipButton;
import bisq.desktop.components.indicator.TxConfidenceIndicator;
import bisq.desktop.util.BsqFormatter;

import bisq.core.btc.listeners.TxConfidenceListener;
import bisq.core.btc.wallet.BsqWalletService;
import bisq.core.dao.DaoPeriodService;
import bisq.core.dao.blockchain.BsqBlockChain;
import bisq.core.dao.blockchain.BsqBlockChainChangeDispatcher;
import bisq.core.dao.blockchain.BsqBlockChainListener;
import bisq.core.dao.blockchain.vo.Tx;
import bisq.core.dao.proposal.Proposal;
import bisq.core.dao.proposal.ProposalCollectionsManager;
import bisq.core.dao.vote.BooleanVoteResult;
import bisq.core.dao.vote.VoteResult;
import bisq.core.locale.Res;

import org.bitcoinj.core.Transaction;
import org.bitcoinj.core.TransactionConfidence;

import javafx.scene.Node;
import javafx.scene.control.Tooltip;
import javafx.scene.image.ImageView;

import javafx.beans.value.ChangeListener;

import java.util.Optional;

import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;
import lombok.extern.slf4j.Slf4j;

@ToString
@Slf4j
@EqualsAndHashCode
public class ProposalListItem implements BsqBlockChainListener {
    @Getter
    private final Proposal proposal;
    private final ProposalCollectionsManager proposalCollectionsManager;
    private final DaoPeriodService daoPeriodService;
    private final BsqWalletService bsqWalletService;
    private final BsqBlockChain bsqBlockChain;
    private final BsqBlockChainChangeDispatcher bsqBlockChainChangeDispatcher;
    private final BsqFormatter bsqFormatter;
    private final ChangeListener<Number> chainHeightListener;
    private final ChangeListener<VoteResult> voteResultChangeListener;
    @Getter
    private TxConfidenceIndicator txConfidenceIndicator;
    @Getter
    private Integer confirmations = 0;

    private TxConfidenceListener txConfidenceListener;
    private Tooltip tooltip = new Tooltip(Res.get("confidence.unknown"));
    private Transaction walletTransaction;
    private ChangeListener<DaoPeriodService.Phase> phaseChangeListener;
    private AutoTooltipButton actionButton;
    private ImageView actionButtonIconView;
    @Setter
    private Runnable onRemoveHandler;
    private Node actionNode;

    ProposalListItem(Proposal proposal,
                     ProposalCollectionsManager proposalCollectionsManager,
                     DaoPeriodService daoPeriodService,
                     BsqWalletService bsqWalletService,
                     BsqBlockChain bsqBlockChain,
                     BsqBlockChainChangeDispatcher bsqBlockChainChangeDispatcher,
                     BsqFormatter bsqFormatter) {
        this.proposal = proposal;
        this.proposalCollectionsManager = proposalCollectionsManager;
        this.daoPeriodService = daoPeriodService;
        this.bsqWalletService = bsqWalletService;
        this.bsqBlockChain = bsqBlockChain;
        this.bsqBlockChainChangeDispatcher = bsqBlockChainChangeDispatcher;
        this.bsqFormatter = bsqFormatter;


        txConfidenceIndicator = new TxConfidenceIndicator();
        txConfidenceIndicator.setId("funds-confidence");

        txConfidenceIndicator.setProgress(-1);
        txConfidenceIndicator.setPrefSize(24, 24);
        txConfidenceIndicator.setTooltip(tooltip);

        actionButton = new AutoTooltipButton();
        actionButton.setMinWidth(70);
        actionButtonIconView = new ImageView();

        chainHeightListener = (observable, oldValue, newValue) -> setupConfidence();
        bsqWalletService.getChainHeightProperty().addListener(chainHeightListener);
        setupConfidence();

        bsqBlockChainChangeDispatcher.addBsqBlockChainListener(this);

        phaseChangeListener = (observable, oldValue, newValue) -> {
            applyState(newValue, proposal.getVoteResult());
        };

        voteResultChangeListener = (observable, oldValue, newValue) -> {
            applyState(daoPeriodService.getPhaseProperty().get(), newValue);
        };

        daoPeriodService.getPhaseProperty().addListener(phaseChangeListener);
        proposal.getVoteResultProperty().addListener(voteResultChangeListener);
        applyState(daoPeriodService.getPhaseProperty().get(), proposal.getVoteResult());
    }

    private void applyState(DaoPeriodService.Phase newValue, VoteResult voteResult) {
        actionButton.setText("");
        actionButton.setVisible(false);
        actionButton.setOnAction(null);

        switch (newValue) {
            case UNDEFINED:
                break;
            case COMPENSATION_REQUESTS:
                if (proposalCollectionsManager.isMine(proposal)) {
                    actionButton.setVisible(!proposal.isClosed());
                    actionButtonIconView.setVisible(actionButton.isVisible());
                    actionButton.setText(Res.get("shared.remove"));
                    actionButton.setGraphic(actionButtonIconView);
                    actionButtonIconView.setId("image-remove");
                    actionButton.setOnAction(e -> onRemoveHandler.run());
                    actionNode = actionButton;
                }
                break;
            case BREAK1:
                break;
            case OPEN_FOR_VOTING:
                if (!proposal.isClosed()) {
                    actionNode = actionButtonIconView;
                    actionButton.setVisible(false);
                    if (proposal.getVoteResult() != null) {
                        actionButtonIconView.setVisible(true);
                        if (voteResult instanceof BooleanVoteResult) {
                            if (((BooleanVoteResult) voteResult).isAccepted()) {
                                actionButtonIconView.setId("accepted");
                            } else {
                                actionButtonIconView.setId("rejected");
                            }
                        } else {
                            //TODO
                        }
                    } else {
                        log.error("actionButtonIconView.setVisible(false);");
                        actionButtonIconView.setVisible(false);
                    }
                }
                break;
            case BREAK2:
                break;
            case VOTE_REVEAL:
                break;
            case BREAK3:
                break;
        }
        actionButton.setManaged(actionButton.isVisible());

        // Don't set managed as otherwise the update does not work (not sure why but probably table
        // cell item issue)
        //actionButtonIconView.setManaged(actionButtonIconView.isVisible());
    }

    @Override
    public void onBsqBlockChainChanged() {
        setupConfidence();
    }

    private void setupConfidence() {
        final Tx tx = bsqBlockChain.getTxMap().get(proposal.getProposalPayload().getTxId());
        if (tx != null) {
            final String txId = tx.getId();

            // We cache the walletTransaction once found
            if (walletTransaction == null) {
                final Optional<Transaction> transactionOptional = bsqWalletService.isWalletTransaction(txId);
                transactionOptional.ifPresent(transaction -> walletTransaction = transaction);
            }

            if (walletTransaction != null) {
                // It is our tx so we get confidence updates
                if (txConfidenceListener == null) {
                    txConfidenceListener = new TxConfidenceListener(txId) {
                        @Override
                        public void onTransactionConfidenceChanged(TransactionConfidence confidence) {
                            updateConfidence(confidence.getConfidenceType(), confidence.getDepthInBlocks(), confidence.numBroadcastPeers());
                        }
                    };
                    bsqWalletService.addTxConfidenceListener(txConfidenceListener);
                }
            } else {
                // tx from other users, we dont get confidence updates but as we have the bsq tx we can calculate it
                // we get setupConfidence called at each new block from above listener so no need to register a new listener
                int depth = bsqWalletService.getChainHeightProperty().get() - tx.getBlockHeight() + 1;
                if (depth > 0)
                    updateConfidence(TransactionConfidence.ConfidenceType.BUILDING, depth, -1);
                //log.error("name={}, id ={}, depth={}", compensationRequest.getPayload().getName(), compensationRequest.getPayload().getUid(), depth);
            }

            final TransactionConfidence confidence = bsqWalletService.getConfidenceForTxId(txId);
            if (confidence != null)
                updateConfidence(confidence, confidence.getDepthInBlocks());
        }
    }

    private void updateConfidence(TransactionConfidence confidence, int depthInBlocks) {
        if (confidence != null) {
            updateConfidence(confidence.getConfidenceType(), confidence.getDepthInBlocks(), confidence.numBroadcastPeers());
            confirmations = depthInBlocks;
        }
    }

    public void cleanup() {
        bsqBlockChainChangeDispatcher.removeBsqBlockChainListener(this);
        bsqWalletService.getChainHeightProperty().removeListener(chainHeightListener);
        if (txConfidenceListener != null)
            bsqWalletService.removeTxConfidenceListener(txConfidenceListener);

        daoPeriodService.getPhaseProperty().removeListener(phaseChangeListener);
        proposal.getVoteResultProperty().removeListener(voteResultChangeListener);
    }

    private void updateConfidence(TransactionConfidence.ConfidenceType confidenceType, int depthInBlocks, int numBroadcastPeers) {
        switch (confidenceType) {
            case UNKNOWN:
                tooltip.setText(Res.get("confidence.unknown"));
                txConfidenceIndicator.setProgress(0);
                break;
            case PENDING:
                tooltip.setText(Res.get("confidence.seen", numBroadcastPeers > -1 ? numBroadcastPeers : Res.get("shared.na")));
                txConfidenceIndicator.setProgress(-1.0);
                break;
            case BUILDING:
                tooltip.setText(Res.get("confidence.confirmed", depthInBlocks));
                txConfidenceIndicator.setProgress(Math.min(1, (double) depthInBlocks / 6.0));
                break;
            case DEAD:
                tooltip.setText(Res.get("confidence.invalid"));
                txConfidenceIndicator.setProgress(0);
                break;
        }

        txConfidenceIndicator.setPrefSize(24, 24);
    }

    public Node getActionNode() {
        return actionNode;
    }
}

