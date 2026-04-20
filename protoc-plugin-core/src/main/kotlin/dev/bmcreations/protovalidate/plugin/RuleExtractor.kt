package dev.bmcreations.protovalidate.plugin

import com.google.protobuf.DescriptorProtos.FieldOptions
import com.google.protobuf.DescriptorProtos.MessageOptions
import com.google.protobuf.DescriptorProtos.OneofOptions
import com.google.protobuf.ExtensionRegistry

interface RuleExtractor {
    fun createRegistry(): ExtensionRegistry
    fun getFieldRules(options: FieldOptions): FieldRuleSet?
    fun isMessageDisabled(options: MessageOptions): Boolean
    fun isMessageIgnored(options: MessageOptions): Boolean
    fun isOneofRequired(options: OneofOptions): Boolean
    fun getMessageOneofRules(options: MessageOptions): List<MessageOneofRuleSet> = emptyList()
    fun getMessageCelRules(options: MessageOptions): List<MessageCelRule> = emptyList()
}
