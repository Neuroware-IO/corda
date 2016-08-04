package com.r3corda.contracts.asset

import com.r3corda.contracts.clause.AbstractConserveAmount
import com.r3corda.contracts.clause.AbstractIssue
import com.r3corda.contracts.clause.NoZeroSizedOutputs
import com.r3corda.core.contracts.*
import com.r3corda.core.contracts.clauses.*
import com.r3corda.core.crypto.*
import com.r3corda.core.node.services.Wallet
import com.r3corda.core.utilities.Emoji
import java.math.BigInteger
import java.security.PublicKey
import java.util.*

/////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
//
// Cash
//

// Just a fake program identifier for now. In a real system it could be, for instance, the hash of the program bytecode.
val CASH_PROGRAM_ID = Cash()

/**
 * A cash transaction may split and merge money represented by a set of (issuer, depositRef) pairs, across multiple
 * input and output states. Imagine a Bitcoin transaction but in which all UTXOs had a colour
 * (a blend of issuer+depositRef) and you couldn't merge outputs of two colours together, but you COULD put them in
 * the same transaction.
 *
 * The goal of this design is to ensure that money can be withdrawn from the ledger easily: if you receive some money
 * via this contract, you always know where to go in order to extract it from the R3 ledger, no matter how many hands
 * it has passed through in the intervening time.
 *
 * At the same time, other contracts that just want money and don't care much who is currently holding it in their
 * vaults can ignore the issuer/depositRefs and just examine the amount fields.
 */
class Cash : OnLedgerAsset<Currency, Cash.State>() {
    /**
     * TODO:
     * 1) hash should be of the contents, not the URI
     * 2) allow the content to be specified at time of instance creation?
     *
     * Motivation: it's the difference between a state object referencing a programRef, which references a
     * legalContractReference and a state object which directly references both.  The latter allows the legal wording
     * to evolve without requiring code changes. But creates a risk that users create objects governed by a program
     * that is inconsistent with the legal contract.
     */
    override val legalContractReference: SecureHash = SecureHash.sha256("https://www.big-book-of-banking-law.gov/cash-claims.html")
    override val conserveClause: AbstractConserveAmount<State, Currency> = Clauses.ConserveAmount()
    override val clauses = listOf(Clauses.Group())
    override fun extractCommands(tx: TransactionForContract): List<AuthenticatedObject<FungibleAsset.Commands>>
            = tx.commands.select<Cash.Commands>()

    interface Clauses {
        class Group : GroupClauseVerifier<State, Issued<Currency>>() {
            override val ifMatched: MatchBehaviour = MatchBehaviour.END
            override val ifNotMatched: MatchBehaviour = MatchBehaviour.ERROR
            override val clauses = listOf(
                        NoZeroSizedOutputs<State, Currency>(),
                        Issue(),
                        ConserveAmount())

            override fun extractGroups(tx: TransactionForContract): List<TransactionForContract.InOutGroup<State, Issued<Currency>>>
                    = tx.groupStates<State, Issued<Currency>> { it.issuanceDef }
        }

        class Issue : AbstractIssue<State, Currency>(
                sum = { sumCash() },
                sumOrZero = { sumCashOrZero(it) }
        ) {
            override val requiredCommands = setOf(Commands.Issue::class.java)
        }

        class ConserveAmount : AbstractConserveAmount<State, Currency>()
    }

    /** A state representing a cash claim against some party */
    data class State(
            override val amount: Amount<Issued<Currency>>,

            /** There must be a MoveCommand signed by this key to claim the amount */
            override val owner: PublicKey
    ) : FungibleAsset<Currency> {
        constructor(deposit: PartyAndReference, amount: Amount<Currency>, owner: PublicKey)
        : this(Amount(amount.quantity, Issued<Currency>(deposit, amount.token)), owner)

        override val deposit = amount.token.issuer
        override val exitKeys = setOf(deposit.party.owningKey)
        override val contract = CASH_PROGRAM_ID
        override val issuanceDef = amount.token
        override val participants = listOf(owner)

        override fun move(newAmount: Amount<Issued<Currency>>, newOwner: PublicKey): FungibleAsset<Currency>
                = copy(amount = amount.copy(newAmount.quantity, amount.token), owner = newOwner)

        override fun toString() = "${Emoji.bagOfCash}Cash($amount at $deposit owned by ${owner.toStringShort()})"

        override fun withNewOwner(newOwner: PublicKey) = Pair(Commands.Move(), copy(owner = newOwner))
    }

    // Just for grouping
    interface Commands : FungibleAsset.Commands {
        /**
         * A command stating that money has been moved, optionally to fulfil another contract.
         *
         * @param contractHash the contract this move is for the attention of. Only that contract's verify function
         * should take the moved states into account when considering whether it is valid. Typically this will be
         * null.
         */
        data class Move(override val contractHash: SecureHash? = null) : FungibleAsset.Commands.Move, Commands

        /**
         * Allows new cash states to be issued into existence: the nonce ("number used once") ensures the transaction
         * has a unique ID even when there are no inputs.
         */
        data class Issue(override val nonce: Long = newSecureRandom().nextLong()) : FungibleAsset.Commands.Issue, Commands

        /**
         * A command stating that money has been withdrawn from the shared ledger and is now accounted for
         * in some other way.
         */
        data class Exit(override val amount: Amount<Issued<Currency>>) : Commands, FungibleAsset.Commands.Exit<Currency>
    }

    /**
     * Puts together an issuance transaction from the given template, that starts out being owned by the given pubkey.
     */
    fun generateIssue(tx: TransactionBuilder, tokenDef: Issued<Currency>, pennies: Long, owner: PublicKey, notary: Party)
            = generateIssue(tx, Amount(pennies, tokenDef), owner, notary)

    /**
     * Puts together an issuance transaction for the specified amount that starts out being owned by the given pubkey.
     */
    fun generateIssue(tx: TransactionBuilder, amount: Amount<Issued<Currency>>, owner: PublicKey, notary: Party) {
        check(tx.inputStates().isEmpty())
        check(tx.outputStates().map { it.data }.sumCashOrNull() == null)
        val at = amount.token.issuer
        tx.addOutputState(TransactionState(State(amount, owner), notary))
        tx.addCommand(generateIssueCommand(), at.party.owningKey)
    }

    override fun deriveState(txState: TransactionState<State>, amount: Amount<Issued<Currency>>, owner: PublicKey)
        = txState.copy(data = txState.data.copy(amount = amount, owner = owner))
    override fun generateExitCommand(amount: Amount<Issued<Currency>>) = Commands.Exit(amount)
    override fun generateIssueCommand() = Commands.Issue()
    override fun generateMoveCommand() = Commands.Move()
}

// Small DSL extensions.

/**
 * Sums the cash states in the list belonging to a single owner, throwing an exception
 * if there are none, or if any of the cash states cannot be added together (i.e. are
 * different currencies or issuers).
 */
fun Iterable<ContractState>.sumCashBy(owner: PublicKey): Amount<Issued<Currency>> = filterIsInstance<Cash.State>().filter { it.owner == owner }.map { it.amount }.sumOrThrow()

/**
 * Sums the cash states in the list, throwing an exception if there are none, or if any of the cash
 * states cannot be added together (i.e. are different currencies or issuers).
 */
fun Iterable<ContractState>.sumCash(): Amount<Issued<Currency>> = filterIsInstance<Cash.State>().map { it.amount }.sumOrThrow()

/** Sums the cash states in the list, returning null if there are none. */
fun Iterable<ContractState>.sumCashOrNull(): Amount<Issued<Currency>>? = filterIsInstance<Cash.State>().map { it.amount }.sumOrNull()

/** Sums the cash states in the list, returning zero of the given currency+issuer if there are none. */
fun Iterable<ContractState>.sumCashOrZero(currency: Issued<Currency>): Amount<Issued<Currency>> {
    return filterIsInstance<Cash.State>().map { it.amount }.sumOrZero<Issued<Currency>>(currency)
}

/**
 * Returns a map of how much cash we have in each currency, ignoring details like issuer. Note: currencies for
 * which we have no cash evaluate to null (not present in map), not 0.
 */
val Wallet.cashBalances: Map<Currency, Amount<Currency>> get() = states.
        // Select the states we own which are cash, ignore the rest, take the amounts.
        mapNotNull { (it.state.data as? Cash.State)?.amount }.
        // Turn into a Map<Currency, List<Amount>> like { GBP -> (£100, £500, etc), USD -> ($2000, $50) }
        groupBy { it.token.product }.
        // Collapse to Map<Currency, Amount> by summing all the amounts of the same currency together.
        mapValues { it.value.map { Amount(it.quantity, it.token.product) }.sumOrThrow() }

fun Cash.State.ownedBy(owner: PublicKey) = copy(owner = owner)
fun Cash.State.issuedBy(party: Party) = copy(amount = Amount(amount.quantity, issuanceDef.copy(issuer = deposit.copy(party = party))))
fun Cash.State.issuedBy(deposit: PartyAndReference) = copy(amount = Amount(amount.quantity, issuanceDef.copy(issuer = deposit)))
fun Cash.State.withDeposit(deposit: PartyAndReference): Cash.State = copy(amount = amount.copy(token = amount.token.copy(issuer = deposit)))

infix fun Cash.State.`owned by`(owner: PublicKey) = ownedBy(owner)
infix fun Cash.State.`issued by`(party: Party) = issuedBy(party)
infix fun Cash.State.`issued by`(deposit: PartyAndReference) = issuedBy(deposit)
infix fun Cash.State.`with deposit`(deposit: PartyAndReference): Cash.State = withDeposit(deposit)

// Unit testing helpers. These could go in a separate file but it's hardly worth it for just a few functions.

/** A randomly generated key. */
val DUMMY_CASH_ISSUER_KEY by lazy { entropyToKeyPair(BigInteger.valueOf(10)) }
/** A dummy, randomly generated issuer party by the name of "Snake Oil Issuer" */
val DUMMY_CASH_ISSUER by lazy { Party("Snake Oil Issuer", DUMMY_CASH_ISSUER_KEY.public).ref(1) }
/** An extension property that lets you write 100.DOLLARS.CASH */
val Amount<Currency>.CASH: Cash.State get() = Cash.State(Amount(quantity, Issued(DUMMY_CASH_ISSUER, token)), NullPublicKey)
/** An extension property that lets you get a cash state from an issued token, under the [NullPublicKey] */
val Amount<Issued<Currency>>.STATE: Cash.State get() = Cash.State(this, NullPublicKey)