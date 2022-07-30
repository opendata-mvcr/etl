package com.linkedpipes.etl.storage.template;

import com.linkedpipes.etl.executor.api.v1.vocabulary.LP_PIPELINE;
import com.linkedpipes.etl.library.rdf.Statements;
import com.linkedpipes.etl.library.rdf.StatementsBuilder;
import com.linkedpipes.etl.library.template.configuration.ConfigurationException;
import com.linkedpipes.etl.library.template.configuration.ConfigurationFacade;
import com.linkedpipes.etl.library.template.configuration.adapter.rdf.RdfToConfigurationDescription;
import com.linkedpipes.etl.library.template.configuration.model.ConfigurationDescription;
import com.linkedpipes.etl.storage.StorageException;
import com.linkedpipes.etl.storage.template.mapping.MappingFacade;
import com.linkedpipes.etl.storage.template.repository.TemplateRepository;
import com.linkedpipes.etl.storage.unpacker.TemplateSource;
import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.Statement;
import org.eclipse.rdf4j.model.ValueFactory;
import org.eclipse.rdf4j.model.impl.SimpleValueFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import java.io.File;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;


@Service
public class TemplateFacade implements TemplateSource {

    private static final Logger LOG
            = LoggerFactory.getLogger(TemplateFacade.class);

    private final TemplateService service;

    private final MappingFacade mapping;

    private final TemplateRepository repository;

    @Autowired
    public TemplateFacade(
            TemplateService service,
            MappingFacade mapping) {
        this.service = service;
        this.mapping = mapping;
        this.repository = service.getRepository();
    }

    @PostConstruct
    public void initialize() {
        cleanMapping();
    }

    /**
     * Remove mapping for components that are no longer in the system.
     */
    private void cleanMapping() {
        List<String> iriToRemove = mapping.getLocalMapping().stream()
                .filter(iri -> !service.getTemplates().containsKey(iri))
                .collect(Collectors.toList());
        for (String iri : iriToRemove) {
            LOG.debug(
                    "Removing mapping for '{}' as the component was deleted.",
                    iri);
            mapping.remove(iri);
        }
        mapping.save();
    }

    public Template getTemplate(String iri) {
        return service.getTemplates().get(iri);
    }

    public Collection<Template> getTemplates() {
        return service.getTemplates().values();
    }

    public Template getParent(Template template) {
        if (template instanceof ReferenceTemplateRef) {
            ReferenceTemplateRef ref = (ReferenceTemplateRef) template;
            return getTemplate(ref.getTemplate());
        }
        return null;
    }

    public Template getRootTemplate(Template template) {
        if (template instanceof JarTemplateRef) {
            return template;
        } else if (template instanceof ReferenceTemplateRef) {
            ReferenceTemplateRef referenceTemplate = (ReferenceTemplateRef) template;
            return referenceTemplate.getCoreTemplate();

        } else {
            throw new RuntimeException("Unknown component type.");
        }
    }

    /**
     * The path from root (template) to the given template.
     */
    public List<Template> getAncestors(Template template) {
        LinkedList<Template> templates = collectAncestors(template);
        Collections.reverse(templates);
        return templates;
    }

    private LinkedList<Template> collectAncestors(Template template) {
        LinkedList<Template> output = new LinkedList<>();
        while (true) {
            output.add(template);
            if (template.getType() == Template.Type.JAR_TEMPLATE) {
                break;
            } else if (template.getType() == Template.Type.REFERENCE_TEMPLATE) {
                ReferenceTemplateRef reference = (ReferenceTemplateRef) template;
                template = getTemplate(reference.getTemplate());
                if (template == null) {
                    LOG.warn("Missing template for: {}", reference.getIri());
                    break;
                }
            } else {
                throw new RuntimeException("Unknown template type: "
                        + template.getType().name());
            }
        }
        return output;
    }

    public List<Template> getAncestorsWithoutJarTemplate(Template template) {
        LinkedList<Template> templates = collectAncestors(template);
        templates.remove(templates.removeLast());
        Collections.reverse(templates);
        return templates;
    }

    public Collection<Template> getSuccessors(Template template) {
        Map<Template, List<Template>> children = buildChildrenIndex();
        Set<Template> output = new HashSet<>();
        Set<Template> toTest = new HashSet<>();
        toTest.addAll(children.getOrDefault(
                template, Collections.EMPTY_LIST));
        while (!toTest.isEmpty()) {
            Template item = toTest.iterator().next();
            toTest.remove(item);
            if (output.contains(item)) {
                continue;
            }
            List<Template> itemChildren = children.getOrDefault(
                    item, Collections.EMPTY_LIST);
            output.add(item);
            output.addAll(itemChildren);
            toTest.addAll(itemChildren);
        }
        return output;
    }

    private Map<Template, List<Template>> buildChildrenIndex() {
        Map<Template, List<Template>> children = new HashMap<>();
        for (Template item : getTemplates()) {
            if (item.getType() != Template.Type.REFERENCE_TEMPLATE) {
                continue;
            }
            ReferenceTemplateRef reference = (ReferenceTemplateRef) item;
            Template parent = getTemplate(reference.getTemplate());
            List<Template> brothers = children.computeIfAbsent(
                    parent, key -> new LinkedList<>());
            // Create if it does not exist.
            brothers.add(reference);
        }
        return children;
    }

    public Collection<Statement> getInterface(Template template)
            throws StorageException {
        return repository.getInterface(template);
    }

    public Collection<Statement> getInterfaces() throws StorageException {
        List<Statement> output = new ArrayList<>();
        for (Template template : service.getTemplates().values()) {
            output.addAll(getInterface(template));
        }
        return output;
    }

    /**
     * Return template config for execution or as merged parent configuration.
     * Configuration of all ancestors are applied.
     */
    public Collection<Statement> getConfigEffective(Template template)
            throws StorageException {
        // TODO Move to extra class and add caching.
        if (!template.isSupportingControl()) {
            // For template without inheritance control, the current
            // configuration is the effective one.
            return getConfig(template);
        }
        SimpleValueFactory valueFactory = SimpleValueFactory.getInstance();
        List<List<Statement>> configurations = new ArrayList<>();
        for (Template item : getAncestors(template)) {
            configurations.add(new ArrayList<>(getConfig(item)));
        }
        try {
            return ConfigurationFacade.merge(
                    configurations,
                    loadDescription(template),
                    template.getIri() + "/effective/",
                    valueFactory.createIRI(template.getIri()));
        } catch (ConfigurationException ex) {
            throw new StorageException("Can't merge configuration.", ex);
        }
    }

    private ConfigurationDescription loadDescription(Template template)
            throws StorageException {
        List<ConfigurationDescription> candidates =
                RdfToConfigurationDescription.asConfigurationDescriptions(
                        Statements.wrap(
                                getConfigDescription(template)).selector());
        if (candidates.size() != 1) {
            throw new StorageException("Missing description for '{}'.",
                    template.getIri());
        }
        return candidates.get(0);
    }

    /**
     * Return configuration of given template for a dialog.
     */
    public Collection<Statement> getConfig(Template template)
            throws StorageException {
        return repository.getConfig(template);
    }

    /**
     * Return configuration for instances of given template.
     */
    public Collection<Statement> getConfigInstance(Template template)
            throws StorageException {
        ValueFactory valueFactory = SimpleValueFactory.getInstance();
        IRI graph = valueFactory.createIRI(template.getIri() + "/new");
        if (template.getType() == Template.Type.JAR_TEMPLATE) {
            return ConfigurationFacade.createNewFromJarFile(
                    new ArrayList<>(repository.getConfig(template)),
                    loadDescription(template),
                    graph.stringValue(),
                    graph);
        } else {
            return ConfigurationFacade.createNewFromTemplate(
                    new ArrayList<>(repository.getConfig(template)),
                    loadDescription(template),
                    graph.stringValue(),
                    graph);
        }
    }

    public Collection<Statement> getConfigDescription(Template template)
            throws StorageException {
        Template rootTemplate = getRootTemplate(template);
        return repository.getConfigDescription(rootTemplate);
    }

    public File getDialogResource(
            Template template, String dialog, String path) {
        return repository.getDialogFile(template, dialog, path);
    }

    public File getStaticResource(Template template, String path) {
        return repository.getStaticFile(template, path);
    }

    public Template createTemplate(
            Collection<Statement> definition,
            Collection<Statement> configuration)
            throws StorageException {
        return createTemplate(definition, configuration, null);
    }

    public Template createTemplate(
            Collection<Statement> definition,
            Collection<Statement> configuration,
            Collection<Statement> configurationDescription)
            throws StorageException {
        Template template = service.createTemplate(
                definition, configuration, configurationDescription);
        return template;
    }

    public void updateInterface(
            Template template, Collection<Statement> diff)
            throws StorageException {
        service.updateTemplateInterface(template, diff);
    }

    public void updateConfig(
            Template template, Collection<Statement> statements)
            throws StorageException {
        service.updateConfig(template, statements);
    }

    public void remove(Template template) throws StorageException {
        service.remove(template);
        mapping.remove(template.getIri());
        mapping.save();
    }

    public Collection<Statement> getDefinition(Template template)
            throws StorageException {
        return repository.getDefinition(template);
    }

    @Override
    public Collection<Statement> getDefinition(String iri)
            throws StorageException {
        Template template = getTemplate(iri);
        // It requires reference to description which is not presented for
        // reference templates.
        StatementsBuilder output =
                Statements.wrap(getDefinition(template)).builder();
        output.setDefaultGraph(iri);
        if (Template.Type.REFERENCE_TEMPLATE.equals(template.getType())) {
            ValueFactory valueFactory = SimpleValueFactory.getInstance();
            output.addIri(
                    valueFactory.createIRI(iri),
                    LP_PIPELINE.HAS_CONFIGURATION_ENTITY_DESCRIPTION,
                    template.getConfigurationDescription()
            );
        }
        return output;
    }

    @Override
    public Collection<Statement> getConfiguration(String iri)
            throws StorageException {
        return getConfig(getTemplate(iri));
    }

    @Override
    public Collection<Statement> getConfigurationDescription(String iri)
            throws StorageException {
        return getConfigDescription(getTemplate(iri));
    }

    public void reload() {
        service.reload();
    }

}
