# Zandy Touch Webshop Builder

## Introduction

This project uses a multi-agent team that takes a business context file describing an existing photography business
and turns it into a brand, a design, and a working front-end and back-end . The outcome is a real deployment that customers can use
to browse and order productivity services  offered by the business.

NOTE: requires a Claude Pro subscription to use the multi-agent feature

## The Agent Team
A team of agents, from a brand strategist to a deployment engineer, waiting for a prompt to kick off work on the project.

- Brand strategist
- UX / Web designer
- Visual designer
- Architect
- Frontend developer
- Backend developer
- QA engineer
- Deployment engineer

The agents run in this order:

```
brand-strategist -> ux-designer -> visual-designer -> architect (design)
  -> frontend-cljs + backend-clojure (in parallel)
  -> architect (review) -> qa -> deployment-engineer
```

`engineering-standards.md` requires an HTML frontend for this case, so we use `frontend-html` and `backend-clojure`. `frontend-cljs` and `backend-node` are alternatives for other stacks.


### The agents
```
.claude
├── agents
│   ├── architect.md
│   ├── backend-clojure.md
│   ├── backend-node.md
│   ├── brand-strategist.md
│   ├── deployment-engineer.md
│   ├── frontend-cljs.md
│   ├── frontend-html.md
│   ├── qa.md
│   ├── ux-designer.md
│   └── visual-designer.md
└── settings.json
```


### Supporting files
```
.
├── .clj-kondo
│   └── config.edn          # flags libraries that are not allowed
├── business-context.md
├── engineering-standards.md
├── README.md
└── tutorial
    └── prompts.org         # the prompts used to drive the agents
```


## Kicking off the website creation
Since we have a team of agents, we just need a prompt to kick off work. The business idea does not need to be specified in the prompt.
The agents are designed to read the business idea from the _business-context.md_ file if provided.

The prompts used so far are collected in `tutorial/prompts.org`.


## Evals
Planned: build a small, fixed prompt set in `eval-prompts.md`.
