# wazeology

[English](README.md) · **Português (Brasil)**

[![License: Apache-2.0](https://img.shields.io/badge/License-Apache_2.0-blue.svg?style=flat-square)](LICENSE)

Aplique um patch no app do Waze para Android para controlar, via BLE, um painel de instrumentos TFT BLE5 da
Kawasaki compatível com o Rideology.

O patch injeta um pequeno pacote (`com.waze.wazeology`) que lê as orientações de navegação em tempo real de
dentro do próprio Waze e envia frames de navegação Kawasaki `0x14` para o painel. Ele também adiciona uma tela
de gerenciamento, a **"Wazeology"**, um segundo ícone de app, onde você busca, pareia, conecta e lê o log. A
build não toca nos recursos do próprio Waze e troca só o código modificado, então o app se comporta exatamente
como antes, fora a ligação extra com o painel.

![A próxima curva do Waze espelhada em um painel TFT da Kawasaki: uma conversão à direita em 30 m mostrada no painel, ao lado da mesma indicação no celular](docs/example-cluster.jpg)

*A próxima curva aparece no painel, então dá para dispensar o suporte de celular: você pilota guiando só pelo painel, ou pareia um intercomunicador para ouvir as instruções por voz também, com o celular travado no bolso.*

> **Antes de instalar:** este não é um app de baixar e usar com um toque. Ninguém pode te passar uma cópia
> pronta, porque o app do Waze não pode ser redistribuído legalmente. Você baixa a sua própria cópia do Waze e
> aplica o patch em um computador, e depois envia o resultado para o celular com o `adb`, a ferramenta de
> instalação do Android voltada a desenvolvedores, por cabo USB ou pela rede. Tudo roda na linha de comando,
> então, se `adb`, Docker e terminais forem novidade para você, conte com algum aprendizado pela frente ou peça
> ajuda a quem já conhece essas ferramentas.

## Sumário

- [Segurança](#segurança)
- [Contexto](#contexto)
- [Instalação](#instalação)
  - [Dependências](#dependências)
- [Uso](#uso)
- [Aviso legal](#aviso-legal)
- [Licença](#licença)

## Segurança

Este projeto controla o painel de instrumentos de um veículo. Teste tudo com a moto parada antes de confiar
nele em movimento. O `scripts/framecheck.sh` confere o layout dos bytes dos frames sem precisar de hardware, e
o **Log** no aparelho mostra cada indicação com o hex bruto. Quando já estiver pilotando com ele, trate-o como
qualquer outro instrumento do painel e mantenha os olhos na estrada.

Modificar o Waze provavelmente viola os Termos de Serviço dele. Esse risco é seu, no seu aparelho e na sua
conta, e nada aqui isenta você disso. Use por sua conta e risco.

## Contexto

Este é um projeto educacional de engenharia reversa, para os seus próprios aparelhos. A ideia é levar as
orientações de navegação do Waze até o painel TFT de fábrica de uma moto, o mesmo display em que o app
Rideology, da própria Kawasaki, escreve. Você entra com a sua cópia do Waze e com o seu hardware.

O protocolo BLE do painel da Kawasaki usado aqui foi obtido por engenharia reversa de forma independente e
testado em hardware real, uma Kawasaki Z900 SE. Como a conexão fala o mesmo protocolo BLE do Rideology, ele
deveria funcionar em qualquer Kawasaki compatível com o Rideology, mas a Z900 SE é a única confirmada até
agora. Outros modelos e anos-modelo não foram testados. Veja o [`DEVELOPMENT.md`](DEVELOPMENT.md) (em inglês)
para a unidade exata testada e as ressalvas de mercado e ano-modelo.

### Veja também

- [`DEVELOPMENT.md`](DEVELOPMENT.md) (em inglês) cobre todo o mecanismo de decompilação, patch e enxerto
  (graft), além da referência do protocolo BLE5 da Kawasaki.
- [`CLAUDE.md`](CLAUDE.md) reúne as regras de trabalho deste repositório, incluindo a regra de ouro de "nunca
  reconstruir os recursos".

## Instalação

O wazeology é compilado e instalado inteiramente pelos scripts em `scripts/`. A única coisa que você instala na
máquina é o Docker; toda a toolchain do Android roda dentro de uma imagem com versões fixadas.

```bash
cp .env.example .env       # opcional: define DEVICE_SERIAL, fonte de download, etc.
scripts/build-image.sh     # uma vez: monta a imagem da toolchain fixada (~1,5 GB na primeira vez)
scripts/fetch-apk.sh       # baixa o Waze 5.23.0.2 fixado (apkeep -> apk/, no gitignore)
```

### Dependências

- O Docker é a única dependência da máquina. apktool, as build-tools do Android, apkeep, a JDK e o adb rodam
  todos dentro da imagem da toolchain fixada que o `scripts/build-image.sh` monta.
- Um aparelho Android acessível pelo `adb`, por USB ou pela rede, para a etapa de instalação.

Algumas observações sobre entradas e reprodutibilidade:

- APKs e keystores nunca vão para o repositório. O `fetch-apk.sh` baixa a versão fixada do Waze, e a keystore
  de debug é gerada localmente na primeira vez que você compila.
- Por padrão, o download vem do apk-pure, que não exige credenciais. Você pode trocar para o google-play pelo
  `.env` e obter uma cópia idêntica byte a byte; essa fonte exige um e-mail de conta e um token AAS, e o
  `.env.example` mostra como configurar.
- As versões são fixadas para garantir reprodutibilidade: Waze 5.23.0.2, apktool 2.10.0, build-tools 34.0.0,
  android-34, apkeep 1.0.0.

## Uso

Compile e instale de uma vez só (o fetch é idempotente):

```bash
scripts/all.sh             # baixa -> decompila -> aplica patch -> compila -> instala
```

Ou passo a passo:

```bash
scripts/fetch-apk.sh       # apk/base.apk + apk/split_config.*.apk
scripts/decompile.sh       # build/base_apktool
scripts/patch.sh           # injeta 4 hooks smali + a <activity> do atalho
scripts/framecheck.sh      # teste de layout dos bytes do frame, sem a moto
scripts/build.sh           # compila o pacote Wazeology -> dex, enxerta na base intacta, assina
scripts/install.sh         # adb install-multiple (base + splits)
```

Depois, no aparelho: abra o ícone **Wazeology** → **Scan** → toque na sua moto → aceite o pareamento no painel.
(A tela Wazeology é em inglês.) Inicie uma rota no Waze e os frames de navegação passam a fluir para o painel.
O **Log** dentro do app tem exportação por **Share** e **Copy**.

O [`DEVELOPMENT.md`](DEVELOPMENT.md) (em inglês) explica como a build funciona por dentro: o enxerto do APK
dividido em splits, os hooks smali, a regra de ouro de nunca reconstruir os recursos e o próprio protocolo BLE.

## Aviso legal

Sem afiliação, endosso ou vínculo com Waze, Google ou Kawasaki. "Waze" e "Kawasaki" são marcas registradas de
seus respectivos donos, citadas aqui apenas para descrever compatibilidade.

Este repositório não contém código, assets nem dados do Waze ou da Kawasaki. Ele traz um pipeline de build e um
pequeno pacote injetado que rodam sobre a sua própria cópia do Waze, baixada de forma legítima, que o
`fetch-apk.sh` obtém no momento da build. Nada de proprietário é redistribuído. O protocolo BLE do painel da
Kawasaki foi obtido por engenharia reversa independente para fins de interoperabilidade, e não retirado da
documentação ou do código-fonte da Kawasaki.

## Licença

[Apache-2.0](LICENSE)

A licença cobre apenas o código próprio deste repositório: o pipeline de build e o pacote injetado
`com.waze.wazeology`. Ela não licencia, e nem poderia licenciar, nenhum material do Waze ou da Kawasaki. Veja o
[Aviso legal](#aviso-legal).
